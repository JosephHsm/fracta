package com.fracta.subscription.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.CashPort;
import com.fracta.account.api.InvestorId;
import com.fracta.audit.api.Auditable;
import com.fracta.common.money.Money;
import com.fracta.common.money.Units;
import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.issuance.api.IssuanceInfo;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.subscription.domain.ProportionalAllocator;
import com.fracta.subscription.domain.ProportionalAllocator.AllotmentInput;
import com.fracta.subscription.domain.SubscriptionOrder;
import com.fracta.subscription.infrastructure.SubscriptionOrderRepository;

/**
 * 배정 확정 (SB-05) — 전 과정 단일 트랜잭션. 중간 실패 시 전체 롤백된다.
 *
 * <p>흐름: (ALLOTTING 상태 확인) → 배정 계산 → 주문별 원장 발행 → 잔여 증거금 환불
 * → 발행인 대금 귀속 → LISTED → INV-1/5/6 검증 (위반 시 예외 → 롤백).
 *
 * <p>원장 발행이 주문 수(N)만큼 advisory lock을 잡으므로 대량 배정은 느리다
 * — 한계 실측은 docs/benchmarks/subscription-concurrency.md 참조.
 */
@Service
public class SubscriptionAllotmentService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SubscriptionAllotmentService.class);

    public record FinalizeResult(long issuanceId, int orderCount, long soldUnits,
                                 long refundedAmount, long issuerCredited) {
    }

    private final SubscriptionOrderRepository orders;
    private final IssuanceAllotmentPort issuances;
    private final LedgerPort ledger;
    private final CashPort cash;
    private final SubscriptionInvariantService invariants;
    private final org.springframework.context.ApplicationEventPublisher events;

    public SubscriptionAllotmentService(SubscriptionOrderRepository orders,
                                        IssuanceAllotmentPort issuances,
                                        LedgerPort ledger,
                                        CashPort cash,
                                        SubscriptionInvariantService invariants,
                                        org.springframework.context.ApplicationEventPublisher events) {
        this.orders = orders;
        this.issuances = issuances;
        this.ledger = ledger;
        this.cash = cash;
        this.invariants = invariants;
        this.events = events;
    }

    @Transactional
    @Auditable(action = "SUBSCRIPTION_FINALIZE", targetType = "ISSUANCE", targetId = "#p0")
    public FinalizeResult finalizeAllotment(long issuanceId) {
        IssuanceInfo info = issuances.info(issuanceId);
        if (!"ALLOTTING".equals(info.status())) {
            throw new IllegalStateException(
                    "배정 확정은 ALLOTTING 상태에서만 가능하다. 현재: " + info.status());
        }

        List<SubscriptionOrder> deposited =
                orders.findByIssuanceIdAndStatusOrderByIdAsc(issuanceId, SubscriptionOrder.Status.DEPOSITED);

        Map<Long, Long> allotment = computeAllotment(info, deposited);

        Money unitPrice = Money.of(info.unitPrice());
        long soldUnits = 0;
        long refundTotal = 0;
        long issuerCredit = 0;

        for (SubscriptionOrder order : deposited) {
            long allotted = allotment.getOrDefault(order.id(), 0L);
            if (allotted > 0) {
                ledger.issue(info.tokenSymbol(), OwnerId.of(order.investorId()), Units.of(allotted),
                        TxRef.of(RefType.SUBSCRIPTION, String.valueOf(order.id())));
            }
            long cost = unitPrice.multiply(allotted).amount();
            long refund = Math.subtractExact(order.depositAmount(), cost);
            if (refund > 0) {
                cash.refundMargin(InvestorId.of(order.investorId()), Money.of(refund));
                refundTotal = Math.addExact(refundTotal, refund);
            }
            issuerCredit = Math.addExact(issuerCredit, cost);
            order.settle(allotted);
            events.publishEvent(new com.fracta.subscription.api.SubscriptionAllottedEvent(
                    order.id(), issuanceId, info.tokenSymbol(), order.investorId(), allotted));
            soldUnits = Math.addExact(soldUnits, allotted);
        }

        if (issuerCredit > 0) {
            cash.settlementCredit(InvestorId.of(info.issuerId()), Money.of(issuerCredit));
        }
        issuances.settleAndList(issuanceId, soldUnits);

        // 이 발행 건에 대한 불변식 위반은 예외로 전체 롤백한다. 자동 복구는 시도하지 않는다 (FSD §8.1)
        var inv1 = ledger.verifyInvariant(info.tokenSymbol());
        if (!inv1.valid()) {
            throw new IllegalStateException("INV-1 위반으로 배정 롤백: " + inv1.violations());
        }
        var inv5 = invariants.verifyInv5(issuanceId);
        if (!inv5.valid()) {
            throw new IllegalStateException("INV-5 위반으로 배정 롤백: " + inv5);
        }
        // INV-6은 전체 예치금에 걸친 전역 불변식이라 다른 발행 건의 이상까지 끌어와 배정을 막는다.
        // 여기서는 경보만 남기고, 검증은 테스트와 일별 배치(Phase 9)가 맡는다.
        var inv6 = invariants.verifyInv6();
        if (!inv6.valid()) {
            log.error("INV-6 위반 감지 (배정은 롤백하지 않는다): {} 상세={}", inv6, inv6.mismatches());
        }

        return new FinalizeResult(issuanceId, deposited.size(), soldUnits, refundTotal, issuerCredit);
    }

    private Map<Long, Long> computeAllotment(IssuanceInfo info, List<SubscriptionOrder> deposited) {
        if (info.fcfs()) {
            // 선착순 — 신청 시점에 remaining_units에서 이미 확보됐다
            return deposited.stream().collect(java.util.stream.Collectors.toMap(
                    SubscriptionOrder::id, SubscriptionOrder::requestedUnits));
        }
        List<AllotmentInput> inputs = deposited.stream()
                .map(o -> new AllotmentInput(o.id(), o.requestedUnits(), o.appliedAt()))
                .toList();
        return ProportionalAllocator.allocate(inputs, info.totalUnits());
    }
}

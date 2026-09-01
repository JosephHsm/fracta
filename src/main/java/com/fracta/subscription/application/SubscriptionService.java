package com.fracta.subscription.application;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.CashPort;
import com.fracta.account.api.InvestorId;
import com.fracta.account.api.RiskGrade;
import com.fracta.account.api.RiskProfileRequiredException;
import com.fracta.account.api.SuitabilityMismatchException;
import com.fracta.account.api.SuitabilityPort;
import com.fracta.account.api.SuitabilityResult;
import com.fracta.audit.api.Auditable;
import com.fracta.common.money.Money;
import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.issuance.api.IssuanceInfo;
import com.fracta.issuance.api.NotSubscribingException;
import com.fracta.subscription.domain.CancellationNotAllowedException;
import com.fracta.subscription.domain.ForbiddenOrderAccessException;
import com.fracta.subscription.domain.IdempotencyConflictException;
import com.fracta.subscription.domain.SubscriptionOrder;
import com.fracta.subscription.infrastructure.SubscriptionOrderRepository;

/**
 * 청약 신청(SB-01/02)·취소(SB-04)·멱등성(SB-06).
 *
 * <p>apply는 트랜잭션 밖 래퍼다 — 멱등성 키 UNIQUE 충돌 시 롤백된 뒤
 * 기존 레코드를 조회해 최초 결과를 반환해야 하기 때문. 실제 처리는 {@link #applyTx}.
 */
@Service
public class SubscriptionService {

    /** UNIQUE 충돌 후 승자의 커밋을 기다리는 최대 시간. */
    private static final java.time.Duration IDEMPOTENCY_WAIT = java.time.Duration.ofSeconds(10);

    public record ApplyResult(long orderId, long requestedUnits, long depositAmount,
                              String status, boolean replayed) {
    }

    private final SubscriptionOrderRepository orders;
    // 자기 호출 시 프록시(@Transactional) 경유를 위한 지연 참조
    private final org.springframework.beans.factory.ObjectProvider<SubscriptionService> selfProvider;

    private final IssuanceAllotmentPort issuances;
    private final SuitabilityPort suitability;
    private final CashPort cash;
    private final AllotmentStrategySelector strategies;

    public SubscriptionService(SubscriptionOrderRepository orders,
                               IssuanceAllotmentPort issuances,
                               SuitabilityPort suitability,
                               CashPort cash,
                               AllotmentStrategySelector strategies,
                               org.springframework.beans.factory.ObjectProvider<SubscriptionService> selfProvider) {
        this.orders = orders;
        this.issuances = issuances;
        this.suitability = suitability;
        this.cash = cash;
        this.strategies = strategies;
        this.selfProvider = selfProvider;
    }

    /** 청약 신청 — 멱등: 동일 키 재요청은 최초 결과를 반환한다. */
    public ApplyResult apply(long issuanceId, InvestorId investorId, long units, String idempotencyKey) {
        var existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), investorId, idempotencyKey);
        }
        try {
            return selfProvider.getObject().applyTx(issuanceId, investorId, units, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청과의 UNIQUE 충돌 — 이 트랜잭션(예약·증거금 차감 포함)은 전부 롤백됐다.
            // 승자가 아직 커밋 전일 수 있으므로 잠깐 기다리며 최초 결과를 찾는다.
            return awaitWinner(idempotencyKey)
                    .map(order -> replay(order, investorId, idempotencyKey))
                    .orElseThrow(() -> e);
        }
    }

    private java.util.Optional<SubscriptionOrder> awaitWinner(String idempotencyKey) {
        long deadline = System.nanoTime() + IDEMPOTENCY_WAIT.toNanos();
        while (true) {
            var found = orders.findByIdempotencyKey(idempotencyKey);
            if (found.isPresent() || System.nanoTime() > deadline) {
                return found;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return orders.findByIdempotencyKey(idempotencyKey);
            }
        }
    }

    @Transactional
    @Auditable(action = "SUBSCRIPTION_APPLY", targetType = "SUBSCRIPTION_ORDER",
            targetId = "#result.orderId()")
    public ApplyResult applyTx(long issuanceId, InvestorId investorId, long units, String idempotencyKey) {
        if (units <= 0) {
            throw new com.fracta.ledger.api.InvalidUnitsRangeException(units);
        }
        IssuanceInfo info = issuances.info(issuanceId);
        if (!info.subscribing()) {
            throw new NotSubscribingException(issuanceId, info.status());
        }

        // 적합성 판정 — 증거금 차감 이전에 수행한다 (차단 시 환불 로직 자체가 없도록)
        RiskGrade productGrade = RiskGrade.fromLevel(info.riskGrade());
        SuitabilityResult suitabilityResult = suitability.check(investorId, productGrade);
        switch (suitabilityResult.decision()) {
            case BLOCKED_NO_PROFILE -> throw new RiskProfileRequiredException(investorId);
            case BLOCKED_MISMATCH ->
                    throw new SuitabilityMismatchException(productGrade, suitabilityResult.investorGrade());
            default -> {
            }
        }

        // 증거금 = 수량 × 단가 (Money.multiply — 오버플로우 검증)
        Money margin = Money.of(info.unitPrice()).multiply(units);

        // 멱등성 키를 가장 먼저 선점한다. 동시 요청의 패자는 재고·증거금을 건드리기 전에
        // UNIQUE 충돌로 걸러져 최초 결과 재생 경로로 넘어간다.
        SubscriptionOrder order = orders.saveAndFlush(new SubscriptionOrder(
                issuanceId, investorId.value(), units, margin.amount(), idempotencyKey));

        if (info.fcfs()) {
            strategies.active().reserve(issuanceId, units);
        }
        cash.holdMargin(investorId, margin);

        return new ApplyResult(order.id(), units, margin.amount(), order.status().name(), false);
    }

    /** 청약 취소 (SB-04) — 청약 기간 내에만. 증거금 전액 환불 + 잔여 수량 복구. */
    @Transactional
    @Auditable(action = "SUBSCRIPTION_CANCEL", targetType = "SUBSCRIPTION_ORDER", targetId = "#p0")
    public ApplyResult cancel(long orderId, InvestorId investorId) {
        SubscriptionOrder order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("청약이 없다: " + orderId));
        if (order.investorId() != investorId.value()) {
            throw new ForbiddenOrderAccessException(orderId);
        }
        if (order.status() != SubscriptionOrder.Status.DEPOSITED) {
            throw new CancellationNotAllowedException(orderId, "이미 처리된 청약이다: " + order.status());
        }
        IssuanceInfo info = issuances.info(order.issuanceId());
        if (!info.subscribing() || Instant.now().isAfter(info.subscriptionEndAt())) {
            throw new CancellationNotAllowedException(orderId, "청약 기간이 아니다");
        }

        cash.refundMargin(investorId, Money.of(order.depositAmount()));
        if (info.fcfs()) {
            strategies.active().release(order.issuanceId(), order.requestedUnits());
        }
        order.markCancelled();
        return new ApplyResult(order.id(), order.requestedUnits(), order.depositAmount(),
                order.status().name(), false);
    }

    @Transactional(readOnly = true)
    public java.util.List<SubscriptionOrder> ordersOf(InvestorId investorId) {
        return orders.findByInvestorIdOrderByIdDesc(investorId.value());
    }

    private ApplyResult replay(SubscriptionOrder order, InvestorId requester, String idempotencyKey) {
        if (order.investorId() != requester.value()) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return new ApplyResult(order.id(), order.requestedUnits(), order.depositAmount(),
                order.status().name(), true);
    }
}

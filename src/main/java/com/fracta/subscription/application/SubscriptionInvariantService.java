package com.fracta.subscription.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.issuance.api.IssuanceInfo;
import com.fracta.subscription.domain.SubscriptionOrder;
import com.fracta.subscription.infrastructure.SubscriptionOrderRepository;

/** INV-5(배정 총량)·INV-6(예치금 보존) 검증 (FSD §8.1). */
@Service
public class SubscriptionInvariantService {

    public record Inv5Result(long issuanceId, boolean valid, long expected, long sumAllotted) {
    }

    public record Inv6Result(boolean valid, long externalNet, long cashBalanceSum, long outstandingMargin,
                             List<String> mismatches, Map<String, Long> cashFlowByType) {

        public long gap() {
            return cashBalanceSum + outstandingMargin - externalNet;
        }
    }

    private final SubscriptionOrderRepository orders;
    private final IssuanceAllotmentPort issuances;
    private final AccountQueryPort accounts;

    public SubscriptionInvariantService(SubscriptionOrderRepository orders,
                                        IssuanceAllotmentPort issuances,
                                        AccountQueryPort accounts) {
        this.orders = orders;
        this.issuances = issuances;
        this.accounts = accounts;
    }

    /** INV-5: Σ allotted_units == min(total_units, Σ requested). 완판이면 total_units와 일치. */
    @Transactional(readOnly = true)
    public Inv5Result verifyInv5(long issuanceId) {
        IssuanceInfo info = issuances.info(issuanceId);
        long requested = orders.sumRequestedUnitsExcluding(issuanceId, SubscriptionOrder.Status.CANCELLED);
        long allotted = orders.sumAllottedUnits(issuanceId);
        long expected = Math.min(info.totalUnits(), requested);
        return new Inv5Result(issuanceId, allotted == expected, expected, allotted);
    }

    /**
     * INV-6: Σ investor.cash_balance + Σ 미결제 증거금 == 총입금 − 총출금.
     * 증거금 홀드/환불/정산은 내부 이동이라 외부 순유입에 포함되지 않는다.
     */
    @Transactional(readOnly = true)
    public Inv6Result verifyInv6() {
        long externalNet = accounts.externalNetDeposits();
        long cashSum = accounts.sumCashBalances();
        long outstanding = orders.sumDepositAmountByStatus(SubscriptionOrder.Status.DEPOSITED);
        boolean valid = cashSum + outstanding == externalNet;
        // 위반 시에만 분해한다 (FSD §8.1: 상세 로그). 자동 복구는 시도하지 않는다.
        return new Inv6Result(valid, externalNet, cashSum, outstanding,
                valid ? List.of() : mismatchingInvestors(),
                valid ? Map.of() : accounts.cashFlowByType());
    }

    /**
     * 위반 원인 후보 나열 — 개략치다. 배정이 끝난 투자자는 매수 대금만큼, 발행인은 판매 대금만큼
     * 정상적으로 차이가 나므로 여기 잡힌다. 전역 합계가 어긋났을 때 어디를 볼지 좁히는 용도다.
     */
    private List<String> mismatchingInvestors() {
        Map<Long, Long> marginByInvestor = orders
                .outstandingMarginByInvestor(SubscriptionOrder.Status.DEPOSITED).stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue()));

        // 기록으로 설명되지 않는 잔액 변동이 진짜 원인이다 — 그걸 먼저 보여준다
        return accounts.cashPositions().stream()
                .filter(p -> p.unexplained() != 0)
                .map(p -> "investor=%d 잔액=%d 기록미반영=%d (외부순유입=%d 미결제증거금=%d)".formatted(
                        p.investorId(), p.cashBalance(), p.unexplained(), p.externalNet(),
                        marginByInvestor.getOrDefault(p.investorId(), 0L)))
                .limit(20)
                .toList();
    }
}

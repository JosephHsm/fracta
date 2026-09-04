package com.fracta.subscription.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.issuance.api.IssuanceInfo;
import com.fracta.common.invariant.ReconciliationCheck;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.subscription.api.InvariantCheckPort;
import com.fracta.subscription.domain.SubscriptionOrder;
import com.fracta.subscription.infrastructure.SubscriptionOrderRepository;
import com.fracta.trading.api.TradeHoldPort;

/**
 * INV-5(배정 총량)·INV-6(예치금 보존)·현금 INV-3 검증 (FSD §8.1).
 *
 * <p><b>이 세 불변식의 유일한 정의다.</b> 야간 대사 배치도 {@link InvariantCheckPort}를 통해
 * 여기를 거친다. 예전에는 배치가 같은 검증을 자기 SQL로 갖고 있어 정의가 두 벌이었고,
 * INV-6에 매수 대금 홀드 항을 추가할 때 한쪽만 고쳐 배치가 정상 상태를 위반으로 판정했다.
 */
@Service
public class SubscriptionInvariantService implements InvariantCheckPort {

    public record Inv5Result(long issuanceId, boolean valid, long expected, long sumAllotted) {
    }

    public record Inv6Result(boolean valid, long externalNet, long cashBalanceSum, long outstandingMargin,
                             long outstandingTradeHold, List<String> mismatches,
                             Map<String, Long> cashFlowByType) {

        /** 보존식의 좌변 — 잔액 + 아직 환급되지 않은 내부 홀드 전부. */
        public long heldTotal() {
            return cashBalanceSum + outstandingMargin + outstandingTradeHold;
        }

        public long gap() {
            return heldTotal() - externalNet;
        }
    }

    private final SubscriptionOrderRepository orders;
    private final IssuanceAllotmentPort issuances;
    private final AccountQueryPort accounts;
    private final TradeHoldPort tradeHolds;
    private final ListedTokenPort listedTokens;

    public SubscriptionInvariantService(SubscriptionOrderRepository orders,
                                        IssuanceAllotmentPort issuances,
                                        AccountQueryPort accounts,
                                        TradeHoldPort tradeHolds,
                                        ListedTokenPort listedTokens) {
        this.orders = orders;
        this.issuances = issuances;
        this.accounts = accounts;
        this.tradeHolds = tradeHolds;
        this.listedTokens = listedTokens;
    }

    // ── 배치용 항목별 검증 (InvariantCheckPort) ──────────────────

    /**
     * INV-5 — 배정이 끝난 발행 건만 검증 대상이다. 청약 중이거나 주문이 없는 종목은
     * 비교할 기대값 자체가 없으므로 위반이 아니라 "대상 아님"으로 남긴다.
     */
    @Override
    @Transactional(readOnly = true)
    public ReconciliationCheck checkInv5(String tokenSymbol) {
        var token = listedTokens.findByTokenSymbol(tokenSymbol).orElse(null);
        if (token == null) {
            return ReconciliationCheck.informational("INV-5", tokenSymbol,
                    "발행 정보가 없어 배정 검증 대상이 아님");
        }
        if (!"LISTED".equals(token.status()) && !"SUSPENDED".equals(token.status())) {
            return ReconciliationCheck.informational("INV-5", tokenSymbol,
                    "배정 완료 발행이 아니어서 검증 대상이 아님: status=" + token.status());
        }
        Inv5Result result = verifyInv5(token.issuanceId());
        if (result.sumAllotted() == 0 && result.expected() == 0) {
            return ReconciliationCheck.informational("INV-5", tokenSymbol,
                    "청약 주문이 없어 배정 검증 대상이 아님");
        }
        return ReconciliationCheck.amounts("INV-5", tokenSymbol,
                java.math.BigInteger.valueOf(result.expected()),
                java.math.BigInteger.valueOf(result.sumAllotted()),
                "expectedAllotted=%d, sumAllotted=%d".formatted(
                        result.expected(), result.sumAllotted()));
    }

    /**
     * 전역 검증 — 현금 INV-3과 예치금 보존 INV-6.
     *
     * <p>호출자가 한 스냅샷 트랜잭션으로 묶어야 한다. 나눠 읽으면 자금이 한 곳에서 다른 곳으로
     * 옮겨가는 중간을 보게 되어 정상인데도 위반으로 판정된다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationCheck> checkGlobal() {
        long negativeCash = accounts.countNegativeCashBalances();
        Inv6Result inv6 = verifyInv6();
        return List.of(
                ReconciliationCheck.count("INV-3", ReconciliationCheck.GLOBAL_SCOPE, negativeCash,
                        "음수 현금 잔고 %d건".formatted(negativeCash)),
                ReconciliationCheck.amounts("INV-6", ReconciliationCheck.GLOBAL_SCOPE,
                        java.math.BigInteger.valueOf(inv6.externalNet()),
                        java.math.BigInteger.valueOf(inv6.heldTotal()),
                        ("externalNet=%d, cashBalanceSum=%d, outstandingMargin=%d, "
                                + "outstandingTradeHold=%d").formatted(
                                inv6.externalNet(), inv6.cashBalanceSum(),
                                inv6.outstandingMargin(), inv6.outstandingTradeHold())));
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
     * INV-6: Σ investor.cash_balance + Σ 미결제 증거금 + Σ 미환급 매수 홀드 == 총입금 − 총출금.
     * 증거금 홀드/환불/정산·매수 대금 홀드는 모두 내부 이동이라 외부 순유입에 포함되지 않는다.
     *
     * <p>매수 홀드 항이 빠지면, 매수 주문이 오더북에 올라 있는 동안 홀드액만큼 INV-6이
     * 깨진 것으로 보인다 — 실제로는 투자자 잔액에서 빠져 주문에 묶여 있을 뿐이다.
     */
    @Transactional(readOnly = true)
    public Inv6Result verifyInv6() {
        long externalNet = accounts.externalNetDeposits();
        long cashSum = accounts.sumCashBalances();
        long outstanding = orders.sumDepositAmountByStatus(SubscriptionOrder.Status.DEPOSITED);
        long tradeHold = tradeHolds.outstandingHeldAmount();
        boolean valid = cashSum + outstanding + tradeHold == externalNet;
        // 위반 시에만 분해한다 (FSD §8.1: 상세 로그). 자동 복구는 시도하지 않는다.
        return new Inv6Result(valid, externalNet, cashSum, outstanding, tradeHold,
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

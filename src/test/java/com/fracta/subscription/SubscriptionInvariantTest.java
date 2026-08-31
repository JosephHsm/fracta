package com.fracta.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.common.money.Units;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.subscription.application.SubscriptionAllotmentService;
import com.fracta.subscription.application.SubscriptionInvariantService;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.SubscriptionTestSupport;

/** 배정 확정 후 INV-1(수량 보존)·INV-5(배정 총량)·INV-6(예치금 보존) 검증. */
class SubscriptionInvariantTest extends IntegrationTestBase {

    @Autowired
    SubscriptionService subscriptions;

    @Autowired
    SubscriptionAllotmentService allotment;

    @Autowired
    SubscriptionInvariantService invariants;

    @Autowired
    SubscriptionTestSupport support;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    LedgerPort ledger;

    @Autowired
    AccountQueryPort accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("FCFS 완판 배정: 원장 발행·대금 귀속·LISTED, INV-1/5/6 전부 통과")
    void fcfsFinalizeHoldsAllInvariants() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 200, 3);
        long a = support.investor(3, 20_000);
        long b = support.investor(3, 20_000);
        long c = support.investor(3, 20_000);

        subscriptions.apply(ctx.issuanceId(), InvestorId.of(a), 50, UUID.randomUUID().toString());
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(b), 30, UUID.randomUUID().toString());
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(c), 20, UUID.randomUUID().toString());

        long issuerCashBefore = accounts.cashBalanceOf(InvestorId.of(ctx.issuerId())).amount();

        issuanceService.startAllotment(ctx.issuanceId());
        var result = allotment.finalizeAllotment(ctx.issuanceId());

        assertThat(result.soldUnits()).isEqualTo(100);
        assertThat(result.refundedAmount()).isZero();
        assertThat(result.issuerCredited()).isEqualTo(20_000);   // 100 × 200

        // 원장 반영 — INV-1
        assertThat(ledger.totalIssued(ctx.tokenSymbol())).isEqualTo(Units.of(100));
        assertThat(ledger.balanceOf(ctx.tokenSymbol(), OwnerId.of(a)).units()).isEqualTo(Units.of(50));
        assertThat(ledger.balanceOf(ctx.tokenSymbol(), OwnerId.of(b)).units()).isEqualTo(Units.of(30));
        assertThat(ledger.balanceOf(ctx.tokenSymbol(), OwnerId.of(c)).units()).isEqualTo(Units.of(20));
        assertThat(ledger.verifyInvariant(ctx.tokenSymbol()).valid()).isTrue();

        // INV-5: Σ allotted == total_units
        var inv5 = invariants.verifyInv5(ctx.issuanceId());
        assertThat(inv5.valid()).as(String.valueOf(inv5)).isTrue();
        assertThat(inv5.sumAllotted()).isEqualTo(100);

        // INV-6: 예치금 보존
        var inv6 = invariants.verifyInv6();
        assertThat(inv6.valid()).as("%s mismatches=%s", inv6, inv6.mismatches()).isTrue();

        // 발행인 대금 귀속 + LISTED
        assertThat(accounts.cashBalanceOf(InvestorId.of(ctx.issuerId())).amount())
                .isEqualTo(issuerCashBefore + 20_000);
        assertThat(issuanceService.get(ctx.issuanceId()).status()).isEqualTo(IssuanceStatus.LISTED);

        // 주문 상태 확정
        Long settled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscription_order WHERE issuance_id = ? AND status = 'SETTLED'",
                Long.class, ctx.issuanceId());
        assertThat(settled).isEqualTo(3);
    }

    @Test
    @DisplayName("PRORATA 초과 청약: 비례배분 + 잔여 증거금 환불, INV-1/5/6 통과")
    void prorataFinalizeAllocatesProportionally() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.PRORATA, 100, 100, 3);
        long a = support.investor(3, 10_000);
        long b = support.investor(3, 10_000);
        long c = support.investor(3, 10_000);

        // T=200 > N=100 — PRORATA는 접수 시 재고를 줄이지 않는다
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(a), 80, UUID.randomUUID().toString());
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(b), 60, UUID.randomUUID().toString());
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(c), 60, UUID.randomUUID().toString());
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(100);

        issuanceService.startAllotment(ctx.issuanceId());
        var result = allotment.finalizeAllotment(ctx.issuanceId());

        // 80/60/60 × 100/200 → 40/30/30
        assertThat(result.soldUnits()).isEqualTo(100);
        assertThat(ledger.balanceOf(ctx.tokenSymbol(), OwnerId.of(a)).units()).isEqualTo(Units.of(40));
        assertThat(ledger.balanceOf(ctx.tokenSymbol(), OwnerId.of(b)).units()).isEqualTo(Units.of(30));
        assertThat(ledger.balanceOf(ctx.tokenSymbol(), OwnerId.of(c)).units()).isEqualTo(Units.of(30));

        // 잔여 증거금 환불: a=4000, b/c=3000 → 최종 잔액 = 10000 - 배정액×단가
        assertThat(accounts.cashBalanceOf(InvestorId.of(a)).amount()).isEqualTo(6_000);
        assertThat(accounts.cashBalanceOf(InvestorId.of(b)).amount()).isEqualTo(7_000);
        assertThat(accounts.cashBalanceOf(InvestorId.of(c)).amount()).isEqualTo(7_000);
        assertThat(result.refundedAmount()).isEqualTo(10_000);
        assertThat(result.issuerCredited()).isEqualTo(10_000);

        assertThat(ledger.verifyInvariant(ctx.tokenSymbol()).valid()).isTrue();
        assertThat(invariants.verifyInv5(ctx.issuanceId()).valid()).isTrue();
        var prorataInv6 = invariants.verifyInv6();
        assertThat(prorataInv6.valid())
                .as("%s mismatches=%s", prorataInv6, prorataInv6.mismatches()).isTrue();
        assertThat(issuanceService.get(ctx.issuanceId()).status()).isEqualTo(IssuanceStatus.LISTED);
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isZero();
    }
}

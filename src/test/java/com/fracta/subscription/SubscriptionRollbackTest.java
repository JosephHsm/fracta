package com.fracta.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.common.money.Units;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.infrastructure.HashChainLedgerAdapter;
import com.fracta.subscription.application.SubscriptionAllotmentService;
import com.fracta.subscription.application.SubscriptionInvariantService;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.SubscriptionTestSupport;

/** 배정 중 의도적 예외 → 원장·잔고·예치금·상태 전부 원복 (부분 배정 잔존 금지). */
class SubscriptionRollbackTest extends IntegrationTestBase {

    @SpyBean
    HashChainLedgerAdapter ledgerAdapter;

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
    @DisplayName("두 번째 원장 발행에서 예외 → 배정 전체 롤백")
    void chaosOnSecondIssueRollsBackEverything() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long a = support.investor(3, 5_000);
        long b = support.investor(3, 5_000);
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(a), 10, UUID.randomUUID().toString());
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(b), 20, UUID.randomUUID().toString());
        issuanceService.startAllotment(ctx.issuanceId());

        long issuerCashBefore = accounts.cashBalanceOf(InvestorId.of(ctx.issuerId())).amount();

        AtomicInteger issueCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (issueCalls.incrementAndGet() == 2) {
                throw new RuntimeException("chaos: 의도적 원장 발행 실패");
            }
            return invocation.callRealMethod();
        }).when(ledgerAdapter).issue(anyString(), any(), any(), any());

        assertThatThrownBy(() -> allotment.finalizeAllotment(ctx.issuanceId()))
                .hasMessageContaining("chaos");

        // 완전 롤백 검증
        assertThat(ledger.totalIssued(ctx.tokenSymbol())).isEqualTo(Units.ZERO);
        assertThat(issuanceService.get(ctx.issuanceId()).status()).isEqualTo(IssuanceStatus.ALLOTTING);
        assertThat(accounts.cashBalanceOf(InvestorId.of(a)).amount()).isEqualTo(4_000);   // 증거금 그대로 홀드
        assertThat(accounts.cashBalanceOf(InvestorId.of(b)).amount()).isEqualTo(3_000);
        assertThat(accounts.cashBalanceOf(InvestorId.of(ctx.issuerId())).amount()).isEqualTo(issuerCashBefore);

        Long deposited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscription_order WHERE issuance_id = ? AND status = 'DEPOSITED'",
                Long.class, ctx.issuanceId());
        assertThat(deposited).isEqualTo(2);

        // 예치금 보존(INV-6)과 체인 무결성은 롤백 후에도 성립한다
        var inv6 = invariants.verifyInv6();
        assertThat(inv6.valid()).as("%s mismatches=%s", inv6, inv6.mismatches()).isTrue();
        long maxSeq = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM ledger_transaction", Long.class);
        if (maxSeq > 0) {
            assertThat(ledger.verifyChain(1, maxSeq).valid()).isTrue();
        }
    }
}

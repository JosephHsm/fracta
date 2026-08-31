package com.fracta.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.InsufficientCashException;
import com.fracta.account.api.InvestorId;
import com.fracta.account.application.CashService;
import com.fracta.common.money.Money;
import com.fracta.subscription.application.SubscriptionInvariantService;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

/** INV-6 예치금 보존: Σ cash_balance + Σ 미결제 증거금 == 총입금 − 총출금. */
class CashInvariantIntegrationTest extends IntegrationTestBase {

    @Autowired
    CashService cashService;

    @Autowired
    SubscriptionInvariantService invariantService;

    @Autowired
    AuthTestSupport auth;

    @Test
    @DisplayName("입금·출금 반복 후 INV-6 성립, 초과 출금은 FUND_INSUFFICIENT_CASH + 잔액 불변")
    void inv6HoldsAfterDepositsAndWithdrawals() {
        var alice = auth.signupAndLogin("cash-alice");
        var bob = auth.signupAndLogin("cash-bob");
        InvestorId aliceId = InvestorId.of(alice.id());
        InvestorId bobId = InvestorId.of(bob.id());

        cashService.deposit(aliceId, Money.of(100_000));
        cashService.withdraw(aliceId, Money.of(30_000));
        cashService.deposit(aliceId, Money.of(5_000));
        cashService.deposit(bobId, Money.of(50_000));
        cashService.withdraw(bobId, Money.of(50_000));
        cashService.deposit(bobId, Money.of(777));

        assertThat(cashService.transactionsOf(aliceId)).hasSize(3);

        // 초과 출금 → 거부 + 잔액 불변
        assertThatThrownBy(() -> cashService.withdraw(aliceId, Money.of(75_001)))
                .isInstanceOf(InsufficientCashException.class);
        assertThatThrownBy(() -> cashService.withdraw(bobId, Money.of(778)))
                .isInstanceOf(InsufficientCashException.class);

        var result = invariantService.verifyInv6();
        assertThat(result.valid())
                .as("%s mismatches=%s", result, result.mismatches())
                .isTrue();
    }
}

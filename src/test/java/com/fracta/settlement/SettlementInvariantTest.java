package com.fracta.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.account.application.CashService;
import com.fracta.common.money.Money;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.settlement.application.SettlementReportService;
import com.fracta.subscription.application.SubscriptionInvariantService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

/** DvP 원자성 — 중간 실패 시 증권·대금·잠금이 전부 원복돼야 한다 (FSD §15.1). */
class SettlementInvariantTest extends IntegrationTestBase {

    @SpyBean
    CashService cashService;

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    LedgerPort ledger;

    @Autowired
    AccountQueryPort accounts;

    @Autowired
    SubscriptionInvariantService invariants;

    @Autowired
    SettlementReportService reports;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("대금 이전 직전 예외 → 증권·대금·잠금 전부 원복, 체결 기록 없음")
    void rollsBackEverythingOnCashFailure() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        long sellerCashBefore = accounts.cashBalanceOf(InvestorId.of(seller)).amount();
        long buyerCashBefore = accounts.cashBalanceOf(InvestorId.of(buyer)).amount();
        long executionsBefore = countExecutions(market.tokenSymbol());

        // 대금 이전 시점에 예외를 주입한다
        doThrow(new IllegalStateException("chaos: 의도적 대금 이전 실패"))
                .when(cashService).tradeDebit(any(), any());

        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        // 증권 미이동
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(buyer)).units())
                .isEqualTo(Units.ZERO);
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).units())
                .isEqualTo(Units.of(100));
        // 매도 잠금 유지 — 매도 주문은 살아있다 (ST-02)
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.of(50));
        // 대금 미이동
        assertThat(accounts.cashBalanceOf(InvestorId.of(seller)).amount()).isEqualTo(sellerCashBefore);
        assertThat(accounts.cashBalanceOf(InvestorId.of(buyer)).amount()).isEqualTo(buyerCashBefore);
        // 체결 기록 없음
        assertThat(countExecutions(market.tokenSymbol())).isEqualTo(executionsBefore);

        var inv6 = invariants.verifyInv6();
        assertThat(inv6.valid()).as("%s mismatches=%s", inv6, inv6.mismatches()).isTrue();
    }

    @Test
    @DisplayName("증권 이전 직전 예외 → 이미 옮긴 대금도 함께 롤백된다")
    void rollsBackCashWhenLedgerFails() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        long sellerCashBefore = accounts.cashBalanceOf(InvestorId.of(seller)).amount();
        long buyerCashBefore = accounts.cashBalanceOf(InvestorId.of(buyer)).amount();

        // 대금은 정상 이동시키고 수수료 적립 단계에서 실패시킨다 (증권 이전 직전)
        doAnswer(invocation -> {
            throw new IllegalStateException("chaos: 수수료 적립 실패");
        }).when(cashService).feeIncome(any());

        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        // 대금이 원복됐다 — "증권만 넘어가고 대금이 안 넘어간" 상태가 없다
        assertThat(accounts.cashBalanceOf(InvestorId.of(seller)).amount()).isEqualTo(sellerCashBefore);
        assertThat(accounts.cashBalanceOf(InvestorId.of(buyer)).amount()).isEqualTo(buyerCashBefore);
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(buyer)).units())
                .isEqualTo(Units.ZERO);

        var inv6 = invariants.verifyInv6();
        assertThat(inv6.valid()).as("%s mismatches=%s", inv6, inv6.mismatches()).isTrue();
    }

    @Test
    @DisplayName("매수자 잔액 부족 → 매수 주문 REJECTED, 매도 잠금은 유지된다")
    void insufficientCashRejectsBuyKeepsSellLock() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long poorBuyer = support.investor(100);   // 체결금액 50,000 에 크게 못 미친다
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 50, support.newKey());
        var result = trading.place(market.tokenSymbol(), InvestorId.of(poorBuyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.filledUnits()).isZero();
        // 매도 주문은 살아 있다
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.of(50));
        assertThat(accounts.cashBalanceOf(InvestorId.of(poorBuyer)).amount()).isEqualTo(100);
    }

    @Test
    @DisplayName("정산 리포트 — 당일 체결 건수·금액·수수료가 집계된다 (ST-04)")
    void dailyReport() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        var before = reports.dailyReport(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 100, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 100, support.newKey());

        var after = reports.dailyReport(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));

        assertThat(after.executionCount()).isEqualTo(before.executionCount() + 1);
        assertThat(after.executionAmount()).isEqualTo(before.executionAmount() + 100_000);
        assertThat(after.totalFee()).isEqualTo(before.totalFee() + 30);   // 15 + 15
    }

    @Test
    @DisplayName("수수료는 Money 를 경유한다 — 음수 금액은 애초에 만들 수 없다")
    void feesUseMoney() {
        assertThatThrownBy(() -> Money.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private long countExecutions(String symbol) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_execution WHERE token_symbol = ?", Long.class, symbol);
    }
}

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
import com.fracta.account.api.InsufficientCashException;
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
import com.fracta.trading.domain.OrderBook;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradeOrder;

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
    @DisplayName("결제 불능인 걸린 매수는 호가창에서 빠진다 — 이후 매도를 막지 않는다")
    void unsettleableRestingBuyIsEvictedFromBook() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 50);

        // 매수가 먼저 호가창에 걸린다. 홀드는 1,000 × 50 + 수수료 7 = 50,007
        var buyOrder = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 50, support.newKey());
        long buyerCashHeld = accounts.cashBalanceOf(InvestorId.of(buyer)).amount();
        assertThat(buyerCashHeld).isEqualTo(10_000_000 - 50_007);

        // 그 매수의 결제를 잔액 부족으로 실패시킨다 — 원인은 걸려 있던 매수 쪽이다
        doThrow(new InsufficientCashException(50_007))
                .when(cashService).tradeDebit(any(), any());

        var sellOrder = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        // 원인 주문은 거절되고 호가창에서 빠진다.
        // 예전에는 되살아나 최우선 호가에 박힌 채 이후 매도를 전부 실패시켰다.
        assertThat(statusOf(buyer, buyOrder.orderId())).isEqualTo(OrderStatus.REJECTED);
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.BUY, 10)).isEmpty();
        // 묶여 있던 홀드는 풀려 돌아온다
        assertThat(accounts.cashBalanceOf(InvestorId.of(buyer)).amount()).isEqualTo(10_000_000);

        // 잘못이 없는 매도는 유령이 되지 않는다 — 호가창에 정상으로 남는다.
        // 예전에는 DB에만 미체결로 남고 오더북에서는 사라져 영영 체결되지 않았다.
        assertThat(sellOrder.status()).isEqualTo(OrderStatus.OPEN.name());
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.SELL, 10))
                .containsExactly(new OrderBook.PriceLevel(1_000, 50));

        var inv6 = invariants.verifyInv6();
        assertThat(inv6.valid()).as("%s mismatches=%s", inv6, inv6.mismatches()).isTrue();
    }

    private OrderStatus statusOf(long investorId, long orderId) {
        return trading.ordersOf(InvestorId.of(investorId)).stream()
                .filter(o -> o.id() == orderId)
                .map(TradeOrder::status)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("매수자 잔액 부족 → 접수 단계에서 막힌다, 매도 잠금은 유지된다 (ST-02)")
    void insufficientCashRejectsBuyKeepsSellLock() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long poorBuyer = support.investor(100);   // 체결금액 50,000 에 크게 못 미친다
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        // 예전에는 주문이 만들어져 호가창에 올라간 뒤 결제 단계에서 REJECTED 가 됐다.
        // 이제는 접수 시점에 대금을 홀드하므로 여기서 끊긴다 — 결제 불가능한 주문이
        // 호가창에 남아 이후 매도를 계속 실패시키던 경로 자체가 없어졌다.
        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(poorBuyer),
                OrderSide.BUY, OrderType.LIMIT, 1_000L, 50, support.newKey()))
                .isInstanceOf(InsufficientCashException.class);

        assertThat(trading.ordersOf(InvestorId.of(poorBuyer))).isEmpty();
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.BUY, 10)).isEmpty();
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

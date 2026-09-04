package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.StaleOrderCleanup;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradeOrder;
import com.fracta.trading.infrastructure.TradeOrderRepository;

/**
 * 미체결 주문 유효기간 (TR-03 확장).
 *
 * <p>유효기간이 없으면 잊힌 주문이 매도 수량과 매수 예치금을 영원히 묶는다.
 * 기본 설정은 무기한(0)이라 스윕이 돌지 않는다 — 여기서는 TTL을 직접 넣어 확인한다.
 */
class StaleOrderCleanupTest extends IntegrationTestBase {

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    TradeOrderRepository orders;

    @Autowired
    LedgerPort ledger;

    @Autowired
    AccountQueryPort accounts;

    @Autowired
    JdbcTemplate jdbc;

    /** 주문을 과거에 낸 것처럼 만든다. createdAt 은 접수 시각이라 테스트에서 직접 민다. */
    private void backdate(long orderId, int days) {
        jdbc.update("UPDATE trade_order SET created_at = now() - make_interval(days => ?) WHERE id = ?",
                days, orderId);
    }

    private StaleOrderCleanup cleanupWithTtl(Duration ttl) {
        return new StaleOrderCleanup(orders, trading, ttl);
    }

    @Test
    @DisplayName("유효기간이 지난 매도 주문은 취소되고 원장 잠금이 풀린다")
    void expiredSellReleasesLedgerLock() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        support.giveUnits(market, seller, 100);

        var placed = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 40, support.newKey());
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.of(40));

        backdate(placed.orderId(), 10);
        int cancelled = cleanupWithTtl(Duration.ofDays(7)).sweep();

        assertThat(cancelled).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(placed.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .as("잊힌 주문이 수량을 계속 묶고 있으면 안 된다")
                .isEqualTo(Units.ZERO);
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.SELL, 10)).isEmpty();
    }

    @Test
    @DisplayName("유효기간이 지난 매수 주문은 취소되고 대금 홀드가 환급된다")
    void expiredBuyRefundsHold() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);
        long before = accounts.cashBalanceOf(InvestorId.of(buyer)).amount();

        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, support.newKey());
        assertThat(accounts.cashBalanceOf(InvestorId.of(buyer)).amount()).isLessThan(before);

        backdate(placed.orderId(), 10);
        cleanupWithTtl(Duration.ofDays(7)).sweep();

        assertThat(statusOf(placed.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(accounts.cashBalanceOf(InvestorId.of(buyer)).amount())
                .as("만료된 매수 주문이 예치금을 계속 묶고 있으면 안 된다")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("유효기간 안의 주문은 건드리지 않는다")
    void freshOrdersSurvive() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);

        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 10, support.newKey());

        backdate(placed.orderId(), 3);
        cleanupWithTtl(Duration.ofDays(7)).sweep();

        assertThat(statusOf(placed.orderId())).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    @DisplayName("TTL 0이면 무기한 — 스윕이 아무것도 건드리지 않는다 (기본값)")
    void zeroTtlDisablesCleanup() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);

        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 10, support.newKey());
        backdate(placed.orderId(), 365);

        assertThat(cleanupWithTtl(Duration.ZERO).sweep()).isZero();
        assertThat(statusOf(placed.orderId())).isEqualTo(OrderStatus.OPEN);
    }

    private OrderStatus statusOf(long orderId) {
        return orders.findById(orderId).map(TradeOrder::status).orElseThrow();
    }
}

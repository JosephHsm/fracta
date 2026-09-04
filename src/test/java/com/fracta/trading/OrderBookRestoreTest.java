package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.InvestorId;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.OrderBookExecutor;
import com.fracta.trading.application.OrderBookRestorer;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderBook;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

/**
 * 오더북 재시작 복원 (FSD §8.4 필수).
 * 컨텍스트를 다시 띄우는 대신 인메모리 오더북을 비워 재시작 상황을 만든다 —
 * 복원 로직이 DB만 보고 오더북을 다시 세우는지가 검증 대상이다.
 */
class OrderBookRestoreTest extends IntegrationTestBase {

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    OrderBookExecutor executor;

    @Autowired
    OrderBookRestorer restorer;

    @Test
    @DisplayName("재시작 후 미체결 주문이 호가창에 동일하게 존재하고 시간 우선순위가 보존된다")
    void restoresOpenOrdersWithTimePriority() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        // 같은 가격에 3건 — 접수 순서가 곧 시간 우선순위다
        var first = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, support.newKey());
        var second = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, support.newKey());
        var third = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, support.newKey());

        List<OrderBook.PriceLevel> before = trading.depth(market.tokenSymbol(), OrderSide.SELL, 10);
        assertThat(before).containsExactly(new OrderBook.PriceLevel(1_000, 30));

        // 재시작 시뮬레이션 — 인메모리 오더북을 통째로 비운다
        executor.runOnPartition(market.tokenSymbol(), OrderBook::drainAll);
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.SELL, 10)).isEmpty();

        var report = restorer.restore();

        // 호가창이 동일하게 복원됐다
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.SELL, 10)).isEqualTo(before);
        assertThat(report.restoredOrders()).isGreaterThanOrEqualTo(3);

        // 시간 우선순위 보존 확인 — 매수가 들어오면 먼저 접수된 주문부터 체결된다
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 10, support.newKey());

        var orders = trading.ordersOf(InvestorId.of(seller));
        long firstFilled = orders.stream().filter(o -> o.id().equals(first.orderId()))
                .findFirst().orElseThrow().filledUnits();
        long secondFilled = orders.stream().filter(o -> o.id().equals(second.orderId()))
                .findFirst().orElseThrow().filledUnits();
        long thirdFilled = orders.stream().filter(o -> o.id().equals(third.orderId()))
                .findFirst().orElseThrow().filledUnits();

        assertThat(firstFilled).as("가장 먼저 접수된 주문이 먼저 체결돼야 한다").isEqualTo(10);
        assertThat(secondFilled).isZero();
        assertThat(thirdFilled).isZero();
    }

    @Test
    @DisplayName("복원 후 원장 locked_units 와 오더북 잠금 수량이 일치한다")
    void lockedUnitsConsistentAfterRestore() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 40, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_200L, 20, support.newKey());

        executor.runOnPartition(market.tokenSymbol(), OrderBook::drainAll);
        var report = restorer.restore();

        assertThat(report.consistent())
                .as("잠금 불일치: %s", report.mismatches())
                .isTrue();
    }
}

package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.InvestorId;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradingExceptions;

/**
 * 주문 멱등성 재생 (TR-01).
 *
 * <p>재생은 <b>같은 사람이 같은 주문을 다시 보냈을 때만</b> 성립한다. 확인 없이 최초 결과를
 * 돌려주면 남의 주문 상태가 새어 나가고, 내용이 달라도 접수된 것처럼 보인다.
 * 청약(SB-06)은 이미 소유자를 확인하고 있었는데 유통만 빠져 있었다.
 */
class OrderIdempotencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Test
    @DisplayName("같은 사람이 같은 주문을 다시 보내면 최초 결과를 그대로 돌려준다")
    void sameRequestReplays() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);
        String key = support.newKey();

        var first = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, key);
        var second = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.orderId()).isEqualTo(first.orderId());
        // 주문이 두 번 생기지 않았다 — 홀드도 한 번만 잡혔다
        assertThat(trading.ordersOf(InvestorId.of(buyer))).hasSize(1);
    }

    @Test
    @DisplayName("남의 멱등성 키로는 주문 상태를 볼 수 없다")
    void otherInvestorCannotReplay() {
        var market = support.listedMarket(null, 100);
        long owner = support.investor(1_000_000);
        long attacker = support.investor(1_000_000);
        String key = support.newKey();

        trading.place(market.tokenSymbol(), InvestorId.of(owner), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, key);

        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(attacker),
                OrderSide.BUY, OrderType.LIMIT, 1_000L, 40, key))
                .isInstanceOf(TradingExceptions.IdempotencyConflictException.class);

        // 공격자에게는 주문이 생기지도, 남의 주문이 보이지도 않는다
        assertThat(trading.ordersOf(InvestorId.of(attacker))).isEmpty();
    }

    @Test
    @DisplayName("같은 키에 다른 수량을 실으면 거부한다 — 최초 결과를 조용히 돌려주지 않는다")
    void differentUnitsConflicts() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);
        String key = support.newKey();

        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, key);

        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.BUY, OrderType.LIMIT, 1_000L, 41, key))
                .isInstanceOf(TradingExceptions.IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("같은 키에 다른 가격·방향을 실어도 거부한다")
    void differentPriceOrSideConflicts() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);
        String key = support.newKey();

        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, key);

        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.BUY, OrderType.LIMIT, 1_100L, 40, key))
                .isInstanceOf(TradingExceptions.IdempotencyConflictException.class);

        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.SELL, OrderType.LIMIT, 1_000L, 40, key))
                .isInstanceOf(TradingExceptions.IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("같은 키를 다른 종목에 재사용하면 거부한다")
    void differentSymbolConflicts() {
        var first = support.listedMarket(null, 100);
        var other = support.listedMarket(null, 100);
        long buyer = support.investor(1_000_000);
        String key = support.newKey();

        trading.place(first.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, key);

        assertThatThrownBy(() -> trading.place(other.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.BUY, OrderType.LIMIT, 1_000L, 40, key))
                .isInstanceOf(TradingExceptions.IdempotencyConflictException.class);
    }
}

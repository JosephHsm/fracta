package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.InvestorId;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingHours;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradingExceptions;

/**
 * 주문 접수 단계의 가격 규칙 (TR-01/02).
 *
 * <p>이 규칙이 없으면 괴리율 자동 거래중단(TR-08)이 유일한 가격 안전장치가 된다. 그건 체결이
 * 일어난 뒤에야 도는 사후 조치이고, 되돌리는 비용(전 주문 취소)이 훨씬 크다.
 *
 * <p>시드 발행가가 1,000원이라 기준가도 1,000원이고, 기본 밴드 ±30% 는 700~1,300원이다.
 */
class PriceRuleIntegrationTest extends IntegrationTestBase {

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Test
    @DisplayName("가격제한폭 위를 벗어난 지정가는 접수 단계에서 거부된다")
    void rejectsPriceAboveDailyLimit() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(100_000_000);

        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.BUY, OrderType.LIMIT, 2_000L, 1, support.newKey()))
                .isInstanceOf(TradingExceptions.PriceOutOfLimitException.class)
                .hasMessageContaining("700")
                .hasMessageContaining("1300");

        // 주문이 만들어지지 않았고 호가창에도 없다
        assertThat(trading.ordersOf(InvestorId.of(buyer))).isEmpty();
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.BUY, 10)).isEmpty();
    }

    @Test
    @DisplayName("가격제한폭 아래를 벗어난 지정가도 거부된다")
    void rejectsPriceBelowDailyLimit() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        support.giveUnits(market, seller, 10);

        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(seller),
                OrderSide.SELL, OrderType.LIMIT, 100L, 1, support.newKey()))
                .isInstanceOf(TradingExceptions.PriceOutOfLimitException.class);
    }

    @Test
    @DisplayName("경계값은 통과한다 — 상한가·하한가 자체는 유효한 호가다")
    void acceptsPricesAtTheBoundary() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(100_000_000);

        var atUpper = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_300L, 1, support.newKey());
        var atLower = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 700L, 1, support.newKey());

        assertThat(atUpper.orderId()).isPositive();
        assertThat(atLower.orderId()).isPositive();
    }

    @Test
    @DisplayName("호가단위에 맞지 않는 가격은 거부된다 — 1원 차이로 호가를 가로챌 수 없다")
    void rejectsOffTickPrice() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(100_000_000);

        // 1,000원대는 10원 단위다
        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.BUY, OrderType.LIMIT, 1_001L, 1, support.newKey()))
                .isInstanceOf(TradingExceptions.InvalidTickException.class)
                .hasMessageContaining("10원 단위");
    }

    @Test
    @DisplayName("시장가는 가격을 정하지 않으므로 가격 규칙의 대상이 아니다")
    void marketOrdersSkipPriceRules() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(100_000_000);
        support.giveUnits(market, seller, 10);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, support.newKey());
        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.MARKET, null, 10, support.newKey());

        assertThat(placed.filledUnits()).isEqualTo(10);
    }

    @Test
    @DisplayName("기준가는 마지막 체결가를 따라간다 — 체결이 나면 밴드도 옮겨간다")
    void bandFollowsLastExecution() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(100_000_000);
        support.giveUnits(market, seller, 100);

        // 1,300에 체결시켜 기준가를 끌어올린다 (발행가 1,000 → 마지막 체결가 1,300)
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_300L, 10, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_300L, 10, support.newKey());

        // 새 밴드는 910 ~ 1,690. 발행가 기준이면 막혔을 1,600이 통과한다
        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_600L, 1, support.newKey());

        assertThat(placed.orderId()).isPositive();
    }

    @Test
    @DisplayName("거래 시간 게이트 — 마감 후에는 접수하지 않는다")
    void marketClosedRejectsOrders() {
        // 토요일 정오 — 주말은 열지 않는다
        var saturday = Instant.parse("2026-09-05T03:00:00Z");   // KST 토 12:00
        var hours = new TradingHours(true, LocalTime.of(9, 0), LocalTime.of(15, 30),
                Clock.fixed(saturday, ZoneId.of("Asia/Seoul")));

        assertThat(hours.isOpen()).isFalse();
        assertThat(hours.describe()).contains("09:00").contains("15:30");
    }

    @Test
    @DisplayName("거래 시간 게이트를 끄면 상시 접수한다 — 기본값이자 테스트·데모 경로")
    void disabledGateAlwaysOpen() {
        var midnight = Instant.parse("2026-09-05T15:00:00Z");   // KST 일 00:00
        var hours = new TradingHours(false, LocalTime.of(9, 0), LocalTime.of(15, 30),
                Clock.fixed(midnight, ZoneId.of("Asia/Seoul")));

        assertThat(hours.isOpen()).isTrue();
        assertThat(hours.describe()).isEqualTo("상시");
    }
}

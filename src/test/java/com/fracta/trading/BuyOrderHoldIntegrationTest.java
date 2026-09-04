package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InsufficientCashException;
import com.fracta.account.api.InvestorId;
import com.fracta.subscription.application.SubscriptionInvariantService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.api.TradeHoldPort;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.OrderType;

/**
 * 매수 대금 홀드 (접수 선잠금).
 *
 * <p>매도는 원장 수량을 선잠금하는데 매수는 대금을 잠그지 않아, 잔고 없는 매수 주문이
 * 호가창에 올라가 결제 실패를 반복시킬 수 있었다. 접수 시점에 예상 체결금액 + 수수료를
 * 홀드하고 체결분만큼 환급한다.
 *
 * <p>수수료는 체결금액 × 0.015% 원 단위 절사다 (FeePolicy).
 *
 * <p>{@code outstandingHeldAmount()} 는 DB 전역 합계라 다른 테스트가 남긴 미체결 매수 주문이
 * 섞인다. 그래서 절대값이 아니라 <b>이 테스트가 만든 증감</b>만 본다.
 */
class BuyOrderHoldIntegrationTest extends IntegrationTestBase {

    private static final long START_CASH = 1_000_000;

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    AccountQueryPort accounts;

    @Autowired
    TradeHoldPort tradeHolds;

    @Autowired
    SubscriptionInvariantService invariants;

    private long cashOf(long investorId) {
        return accounts.cashBalanceOf(InvestorId.of(investorId)).amount();
    }

    /** 기준선 이후 이 테스트가 늘린 홀드. */
    private long heldSince(long baseline) {
        return tradeHolds.outstandingHeldAmount() - baseline;
    }

    @Test
    @DisplayName("잔고가 모자라면 매수 주문 자체가 생기지 않는다 — 호가창에도 올라가지 않는다")
    void insufficientCashRejectsBeforeOrderExists() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(10_000);
        long held0 = tradeHolds.outstandingHeldAmount();

        // 1,000 × 100 = 100,000 + 수수료 — 잔고 10,000으로는 어림없다
        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.BUY, OrderType.LIMIT, 1_000L, 100, support.newKey()))
                .isInstanceOf(InsufficientCashException.class);

        assertThat(trading.ordersOf(InvestorId.of(buyer))).isEmpty();
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.BUY, 10)).isEmpty();
        assertThat(cashOf(buyer)).isEqualTo(10_000);
        assertThat(heldSince(held0)).isZero();
        assertThat(invariants.verifyInv6().valid()).isTrue();
    }

    @Test
    @DisplayName("매수 접수 시 체결금액 + 수수료가 홀드된다 — 취소하면 전액 환급")
    void holdIsTakenOnAcceptAndRefundedOnCancel() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(START_CASH);
        long held0 = tradeHolds.outstandingHeldAmount();

        // 1,000 × 40 = 40,000, 수수료 = 40,000 × 0.00015 = 6 → 홀드 40,006
        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, support.newKey());

        assertThat(placed.status()).isEqualTo(OrderStatus.OPEN.name());
        assertThat(cashOf(buyer)).isEqualTo(START_CASH - 40_006);
        assertThat(heldSince(held0)).isEqualTo(40_006);
        // 홀드 중에도 예치금 보존식은 성립해야 한다 (잔액에서 빠졌을 뿐 사라진 게 아니다)
        assertThat(invariants.verifyInv6().valid()).isTrue();

        trading.cancel(placed.orderId(), InvestorId.of(buyer));

        assertThat(cashOf(buyer)).isEqualTo(START_CASH);
        assertThat(heldSince(held0)).isZero();
        assertThat(invariants.verifyInv6().valid()).isTrue();
    }

    @Test
    @DisplayName("부분 체결 — 체결분만 결제되고 잔여 홀드는 남았다가 취소 시 환급된다")
    void partialFillKeepsRemainingHold() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(START_CASH);
        support.giveUnits(market, seller, 10);
        long held0 = tradeHolds.outstandingHeldAmount();

        // 매도 10주만 호가창에 있다
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, support.newKey());

        // 매수 40주 — 홀드 40,006, 그중 10주만 체결된다
        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, support.newKey());

        // 체결 원가 = 10,000 + 수수료 1(10,000 × 0.00015 = 1.5 절사) = 10,001
        long paid = 10_001;
        assertThat(placed.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED.name());
        assertThat(placed.filledUnits()).isEqualTo(10);
        assertThat(cashOf(buyer)).isEqualTo(START_CASH - 40_006);
        // 남은 30주 몫의 홀드만 남는다
        assertThat(heldSince(held0)).isEqualTo(40_006 - paid);
        assertThat(invariants.verifyInv6().valid()).isTrue();

        trading.cancel(placed.orderId(), InvestorId.of(buyer));

        // 실제로 낸 돈은 체결분뿐이다
        assertThat(cashOf(buyer)).isEqualTo(START_CASH - paid);
        assertThat(heldSince(held0)).isZero();
        assertThat(invariants.verifyInv6().valid()).isTrue();
    }

    @Test
    @DisplayName("지정가보다 싸게 전량 체결되면 남은 홀드가 그 자리에서 환급된다")
    void leftoverHoldRefundedOnPriceImprovement() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(START_CASH);
        support.giveUnits(market, seller, 10);
        long held0 = tradeHolds.outstandingHeldAmount();

        // 900에 매도가 걸려 있는데 매수는 1,000까지 부른다
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 900L, 10, support.newKey());

        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 10, support.newKey());

        // 홀드는 1,000 기준(10,001)이었지만 결제는 900 기준(9,000 + 1)이다
        assertThat(placed.status()).isEqualTo(OrderStatus.FILLED.name());
        assertThat(cashOf(buyer)).isEqualTo(START_CASH - 9_001);
        // 차액이 홀드에 갇히면 안 된다
        assertThat(heldSince(held0)).isZero();
        assertThat(invariants.verifyInv6().valid()).isTrue();
    }

    @Test
    @DisplayName("시장가 매수 — 호가를 훑어 홀드하고, 못 채운 잔량 몫은 취소되며 환급된다")
    void marketBuyHoldsSweptCostAndRefundsRemainder() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(START_CASH);
        support.giveUnits(market, seller, 15);
        long held0 = tradeHolds.outstandingHeldAmount();

        // 두 호가로 나눠 건다 — 스윕이 호가별 가격을 제대로 밟는지 본다
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_200L, 5, support.newKey());

        // 시장가 40주 — 호가는 15주뿐이라 15주만 체결되고 25주는 취소된다
        var placed = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.MARKET, null, 40, support.newKey());

        // 10 × 1,000 = 10,000 (수수료 1) + 5 × 1,200 = 6,000 (수수료 0) = 16,001
        assertThat(placed.filledUnits()).isEqualTo(15);
        assertThat(placed.status()).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(cashOf(buyer)).isEqualTo(START_CASH - 16_001);
        assertThat(heldSince(held0)).isZero();
        assertThat(invariants.verifyInv6().valid()).isTrue();
    }

    @Test
    @DisplayName("거래 중단으로 전량 취소되면 매수 홀드도 함께 풀린다")
    void suspensionReleasesHolds() {
        var market = support.listedMarket(null, 100);
        long buyer = support.investor(START_CASH);
        long held0 = tradeHolds.outstandingHeldAmount();

        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 40, support.newKey());
        assertThat(heldSince(held0)).isEqualTo(40_006);

        trading.cancelAllOpenOrders(market.tokenSymbol(), "테스트 중단");

        assertThat(cashOf(buyer)).isEqualTo(START_CASH);
        assertThat(heldSince(held0)).isZero();
        assertThat(invariants.verifyInv6().valid()).isTrue();
    }
}

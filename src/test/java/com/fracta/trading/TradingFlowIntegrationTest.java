package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradingExceptions;

class TradingFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    LedgerPort ledger;

    @Autowired
    AccountQueryPort accounts;

    @Test
    @DisplayName("매도 주문 시 잠금이 선행한다 — 보유 수량 초과면 주문이 생성되지 않는다")
    void sellRequiresLockFirst() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        support.giveUnits(market, seller, 50);

        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(seller),
                OrderSide.SELL, OrderType.LIMIT, 1_000L, 51, support.newKey()))
                .isInstanceOf(InsufficientUnitsException.class);

        // 주문 레코드가 생기지 않았고 잠금도 없다
        assertThat(trading.ordersOf(InvestorId.of(seller))).isEmpty();
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.ZERO);
    }

    @Test
    @DisplayName("매도 주문 접수 → 잠금 반영, 가용 수량 감소")
    void sellLocksUnits() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 40, support.newKey());

        var balance = ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller));
        assertThat(balance.units()).isEqualTo(Units.of(100));
        assertThat(balance.lockedUnits()).isEqualTo(Units.of(40));
        assertThat(balance.available()).isEqualTo(Units.of(60));
    }

    @Test
    @DisplayName("체결 — 증권과 대금이 함께 이동하고 수수료가 차감된다 (DvP)")
    void executionMovesBothSides() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        long sellerCashBefore = accounts.cashBalanceOf(InvestorId.of(seller)).amount();
        long buyerCashBefore = accounts.cashBalanceOf(InvestorId.of(buyer)).amount();

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 50, support.newKey());
        var result = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 50, support.newKey());

        assertThat(result.filledUnits()).isEqualTo(50);
        assertThat(result.status()).isEqualTo("FILLED");

        // 증권 이동
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(buyer)).units())
                .isEqualTo(Units.of(50));
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).units())
                .isEqualTo(Units.of(50));
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.ZERO);

        // 대금 이동 — 체결금액 50,000. 요율로는 7원(50,000 × 0.00015 = 7.5 절사)이지만
        // 최소수수료 10원이 걸린다 (ST-03)
        long amount = 50_000;
        long fee = 10;
        assertThat(accounts.cashBalanceOf(InvestorId.of(buyer)).amount())
                .isEqualTo(buyerCashBefore - amount - fee);
        assertThat(accounts.cashBalanceOf(InvestorId.of(seller)).amount())
                .isEqualTo(sellerCashBefore + amount - fee);
    }

    @Test
    @DisplayName("주문 취소 — 미체결 잔량만 취소하고 그만큼만 잠금 해제한다")
    void cancelReleasesOnlyRemaining() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        var sellOrder = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 100, support.newKey());
        // 30만 체결
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 30, support.newKey());

        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.of(70));

        trading.cancel(sellOrder.orderId(), InvestorId.of(seller));

        // 잔량 70만 해제 — 체결된 30은 이미 이전됐다
        var balance = ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller));
        assertThat(balance.lockedUnits()).isEqualTo(Units.ZERO);
        assertThat(balance.units()).isEqualTo(Units.of(70));
    }

    @Test
    @DisplayName("본인 주문이 아니면 취소할 수 없고, 이미 취소된 주문은 재취소 불가")
    void cancelGuards() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long other = support.investor(0);
        support.giveUnits(market, seller, 10);

        var order = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, support.newKey());

        assertThatThrownBy(() -> trading.cancel(order.orderId(), InvestorId.of(other)))
                .isInstanceOf(TradingExceptions.ForbiddenOrderAccessException.class);

        trading.cancel(order.orderId(), InvestorId.of(seller));
        assertThatThrownBy(() -> trading.cancel(order.orderId(), InvestorId.of(seller)))
                .isInstanceOf(TradingExceptions.OrderNotCancellableException.class);
    }

    @Test
    @DisplayName("시장가 매수 — 반대편 호가를 소진하고 잔량은 취소된다")
    void marketOrderFillsThenCancels() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 20, support.newKey());
        var result = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.MARKET, null, 50, support.newKey());

        assertThat(result.filledUnits()).isEqualTo(20);
        assertThat(result.status()).isEqualTo("CANCELLED");   // 잔량 30은 취소
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.BUY, 10)).isEmpty();
    }

    @Test
    @DisplayName("멱등성 — 동일 키 재요청은 최초 결과를 돌려주고 주문을 새로 만들지 않는다")
    void idempotentPlace() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        support.giveUnits(market, seller, 100);
        String key = support.newKey();

        var first = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, key);
        var replay = trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 10, key);

        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.replayed()).isTrue();
        // 잠금이 두 번 잡히지 않았다
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.of(10));
    }

    @Test
    @DisplayName("호가창 조회 — 매수·매도 각각 집계된다")
    void orderBookDepth() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_200L, 30, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 20, support.newKey());

        assertThat(trading.depth(market.tokenSymbol(), OrderSide.SELL, 10))
                .containsExactly(new com.fracta.trading.domain.OrderBook.PriceLevel(1_200, 30));
        assertThat(trading.depth(market.tokenSymbol(), OrderSide.BUY, 10))
                .containsExactly(new com.fracta.trading.domain.OrderBook.PriceLevel(1_000, 20));
    }
}

package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.InvestorId;
import com.fracta.common.money.Units;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradingExceptions;

/**
 * 괴리율 산출·경보 (TR-07, TR-08).
 * Mock 어댑터는 티커에 든 숫자를 기준가로 쓴다 — 이 성질로 괴리율을 원하는 값에 맞춘다.
 */
class PremiumRateIntegrationTest extends IntegrationTestBase {

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    ListedTokenPort listedTokens;

    @Autowired
    LedgerPort ledger;

    @Autowired
    JdbcTemplate jdbc;

    /** 체결 1건을 만든다. 체결가는 sellPrice. */
    private TradingTestSupport.Market executeOnce(String ticker, long splitRatio, long price) {
        var market = support.listedMarket(ticker, splitRatio);
        long seller = support.investor(0);
        long buyer = support.investor(100_000_000);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, price, 10, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, price, 10, support.newKey());
        return market;
    }

    private BigDecimal storedPremium(String symbol) {
        return jdbc.queryForObject(
                "SELECT premium_rate FROM trade_execution WHERE token_symbol = ? ORDER BY id DESC LIMIT 1",
                BigDecimal.class, symbol);
    }

    @Test
    @DisplayName("티커가 없으면 괴리율을 계산하지 않는다 (null 저장) — Mock 만으로도 동작해야 한다")
    void skipsWhenNoTicker() {
        var market = executeOnce(null, 100, 1_000);
        assertThat(storedPremium(market.tokenSymbol())).isNull();
    }

    @Test
    @DisplayName("괴리율 저장 — 참조가와 체결가가 같으면 0 근처")
    void storesNearZeroPremium() {
        // MOCK-100000 → 기준가 100,000(±1%), 분할비율 100 → 참조가 ≈ 1,000
        var market = executeOnce("MOCK-100000", 100, 1_000);
        BigDecimal premium = storedPremium(market.tokenSymbol());
        assertThat(premium).isNotNull();
        assertThat(premium.abs()).isLessThan(new BigDecimal("2"));
    }

    @Test
    @DisplayName("괴리율 10% 초과 → 경고만, 거래는 계속된다 (종목 유지)")
    void warnsAboveTenPercent() {
        // 참조가 ≈ 1,000 인데 1,150 에 체결 → 약 +15%
        var market = executeOnce("MOCK-100000", 100, 1_150);

        BigDecimal premium = storedPremium(market.tokenSymbol());
        assertThat(premium).isGreaterThan(new BigDecimal("10"));
        assertThat(premium).isLessThan(new BigDecimal("20"));

        // 거래는 계속된다 — 여전히 LISTED
        assertThat(listedTokens.findByTokenSymbol(market.tokenSymbol()).orElseThrow().status())
                .isEqualTo("LISTED");
    }

    @Test
    @DisplayName("괴리율 20% 초과 → 자동 SUSPENDED + 신규 주문 거부 + 미체결 주문 취소·잠금 해제")
    void suspendsAboveTwentyPercent() {
        var market = support.listedMarket("MOCK-100000", 100);
        long seller = support.investor(0);
        long buyer = support.investor(100_000_000);
        support.giveUnits(market, seller, 200);

        // 먼저 체결되지 않을 미체결 매도를 하나 걸어둔다 (거래 중단 시 취소 대상)
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 9_000L, 50, support.newKey());
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.of(50));

        // 참조가 ≈ 1,000 인데 1,500 에 체결 → 약 +50%
        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_500L, 10, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_500L, 10, support.newKey());

        BigDecimal premium = storedPremium(market.tokenSymbol());
        assertThat(premium).isGreaterThan(new BigDecimal("20"));

        // 자동 거래 중단
        assertThat(listedTokens.findByTokenSymbol(market.tokenSymbol()).orElseThrow().status())
                .isEqualTo("SUSPENDED");

        // 신규 주문 거부
        assertThatThrownBy(() -> trading.place(market.tokenSymbol(), InvestorId.of(buyer),
                OrderSide.BUY, OrderType.LIMIT, 1_000L, 1, support.newKey()))
                .isInstanceOf(TradingExceptions.NotTradableException.class);

        // 미체결 주문 취소 + 잠금 해제 (정책: 전량 취소)
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.ZERO);
        Long openOrders = jdbc.queryForObject("""
                SELECT COUNT(*) FROM trade_order
                WHERE token_symbol = ? AND status IN ('OPEN', 'PARTIALLY_FILLED')
                """, Long.class, market.tokenSymbol());
        assertThat(openOrders).isZero();
    }
}

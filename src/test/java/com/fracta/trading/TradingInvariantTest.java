package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.InvestorId;
import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.subscription.application.SubscriptionInvariantService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

/** 대량 체결 후 불변식 유지 + 이중 매도 방지 (FSD §14 명시 조건). */
class TradingInvariantTest extends IntegrationTestBase {

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Autowired
    LedgerPort ledger;

    @Autowired
    SubscriptionInvariantService invariants;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("이중 매도 방지 — 보유 100, 매도 100 주문 2건 동시 → 1건만 성공")
    void preventsDoubleSell() throws Exception {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        support.giveUnits(market, seller, 100);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                                OrderType.LIMIT, 1_000L, 100, support.newKey());
                        success.incrementAndGet();
                    } catch (InsufficientUnitsException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get()).isZero();
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        // 잠금은 정확히 100 — 200이 잠기면 이중 매도다
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.of(100));
    }

    @Test
    @DisplayName("대량 체결 후 INV-1~INV-6 전부 통과 + 체인 무결성 유지")
    void invariantsHoldAfterManyExecutions() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(50_000_000);
        support.giveUnits(market, seller, 500);

        // 매도 50건 × 10주 → 매수 50건으로 전량 체결
        for (int i = 0; i < 50; i++) {
            trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                    OrderType.LIMIT, 1_000L, 10, support.newKey());
        }
        AtomicLong filled = new AtomicLong();
        for (int i = 0; i < 50; i++) {
            var r = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                    OrderType.LIMIT, 1_000L, 10, support.newKey());
            filled.addAndGet(r.filledUnits());
        }
        assertThat(filled.get()).isEqualTo(500);

        // INV-1 수량 보존 + INV-2/3 (원장 자체 검증)
        var ledgerInv = ledger.verifyInvariant(market.tokenSymbol());
        assertThat(ledgerInv.valid()).as(String.valueOf(ledgerInv.violations())).isTrue();

        // INV-4 체인 무결성
        long maxSeq = jdbc.queryForObject("SELECT MAX(seq) FROM ledger_transaction", Long.class);
        assertThat(ledger.verifyChain(1, maxSeq).valid()).isTrue();

        // INV-5 배정 총량 (이 종목은 청약 없이 상장됐으므로 배정 대상 없음 → 성립)
        var inv5 = invariants.verifyInv5(market.issuanceId());
        assertThat(inv5.valid()).as(String.valueOf(inv5)).isTrue();

        // INV-6 예치금 보존 — 수수료가 플랫폼 계정으로 가므로 총액이 보존된다
        var inv6 = invariants.verifyInv6();
        assertThat(inv6.valid()).as("gap=%d %s%n유형별=%s%n원인후보=%s", inv6.gap(), inv6, inv6.cashFlowByType(), inv6.mismatches()).isTrue();

        // 매수자·매도자 잔고 확인
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(buyer)).units())
                .isEqualTo(Units.of(500));
        assertThat(ledger.balanceOf(market.tokenSymbol(), OwnerId.of(seller)).lockedUnits())
                .isEqualTo(Units.ZERO);
    }

    @Test
    @DisplayName("DvP 데드락 — 상호 매매(A→B, B→A) 동시 100건에서 데드락 0건")
    void noDeadlockOnCrossTrades() throws Exception {
        var market = support.listedMarket(null, 100);
        long a = support.investor(50_000_000);
        long b = support.investor(50_000_000);
        support.giveUnits(market, a, 200);
        support.giveUnits(market, b, 200);

        int rounds = 50;   // A→B 50건 + B→A 50건 = 100건
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(rounds * 2);
        AtomicInteger failures = new AtomicInteger();
        java.util.Queue<String> errors = new java.util.concurrent.ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < rounds; i++) {
                pool.submit(() -> tradePair(market, a, b, start, done, failures, errors));
                pool.submit(() -> tradePair(market, b, a, start, done, failures, errors));
            }
            start.countDown();
            assertThat(done.await(180, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get())
                .as("실패 상위 5건: %s", errors.stream().distinct().limit(5).toList())
                .isZero();

        // 데드락이 없었다면 불변식도 그대로다
        assertThat(ledger.verifyInvariant(market.tokenSymbol()).valid()).isTrue();
        var inv6 = invariants.verifyInv6();
        assertThat(inv6.valid()).as("gap=%d %s%n유형별=%s%n원인후보=%s", inv6.gap(), inv6, inv6.cashFlowByType(), inv6.mismatches()).isTrue();
    }

    private void tradePair(TradingTestSupport.Market market, long sellerId, long buyerId,
                           CountDownLatch start, CountDownLatch done,
                           AtomicInteger failures, java.util.Queue<String> errors) {
        try {
            start.await();
            trading.place(market.tokenSymbol(), InvestorId.of(sellerId), OrderSide.SELL,
                    OrderType.LIMIT, 1_000L, 2, support.newKey());
            trading.place(market.tokenSymbol(), InvestorId.of(buyerId), OrderSide.BUY,
                    OrderType.LIMIT, 1_000L, 2, support.newKey());
        } catch (Exception e) {
            failures.incrementAndGet();
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            done.countDown();
        }
    }

    @Test
    @DisplayName("SettlementInvariant — 체결 건별 대금·수수료 합이 장부와 일치한다")
    void settlementAmountsMatchLedger() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(10_000_000);
        support.giveUnits(market, seller, 100);

        trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                OrderType.LIMIT, 1_000L, 100, support.newKey());
        trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                OrderType.LIMIT, 1_000L, 100, support.newKey());

        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT price, units, buy_fee, sell_fee FROM trade_execution
                WHERE token_symbol = ?
                """, market.tokenSymbol());
        assertThat(rows).hasSize(1);

        long price = ((Number) rows.getFirst().get("price")).longValue();
        long units = ((Number) rows.getFirst().get("units")).longValue();
        long buyFee = ((Number) rows.getFirst().get("buy_fee")).longValue();
        long sellFee = ((Number) rows.getFirst().get("sell_fee")).longValue();

        // 100,000 × 0.00015 = 15원
        assertThat(price * units).isEqualTo(100_000);
        assertThat(buyFee).isEqualTo(15);
        assertThat(sellFee).isEqualTo(15);

        // 플랫폼 계정이 수수료 합계를 받았다
        Long platformBalance = jdbc.queryForObject(
                "SELECT cash_balance FROM investor WHERE email = 'platform-fee@fracta.internal'",
                Long.class);
        assertThat(platformBalance).isGreaterThanOrEqualTo(buyFee + sellFee);
    }
}

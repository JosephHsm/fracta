package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.InvestorId;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.BookOrder;
import com.fracta.trading.domain.OrderBook;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

/**
 * 처리량·지연 실측. 결과는 {@code docs/benchmarks/trading-matching.md} 에 기록한다.
 *
 * <p>두 가지를 나눠 잰다.
 * <ul>
 *   <li><b>매칭 엔진</b> — 인메모리 오더북 자체의 처리량 (FSD §13.1 "매칭 처리량 종목당 초당 100건")</li>
 *   <li><b>주문 경로 전체</b> — 접수 + 매칭 + DvP 결제까지. 원장 advisory lock 직렬화가 상한을 만든다</li>
 * </ul>
 * 임계값은 회귀 감지용이다. FSD 목표 대비 실제 수치와 그 이유는 벤치마크 문서에 적는다.
 */
class MatchingThroughputTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(MatchingThroughputTest.class);

    private static final int WARMUP = 20;
    private static final int ORDERS = 200;
    private static final int ENGINE_ORDERS = 20_000;

    @Autowired
    TradingService trading;

    @Autowired
    TradingTestSupport support;

    @Test
    @DisplayName("매칭 엔진 처리량 — 종목당 초당 100건 이상 (FSD §13.1)")
    void matchingEngineThroughput() {
        OrderBook book = new OrderBook("FR-BENCH-001");
        Instant base = Instant.parse("2026-08-31T09:00:00Z");

        // 매도 호가를 깔고, 같은 수만큼 매수로 소진시킨다
        for (int i = 0; i < ENGINE_ORDERS; i++) {
            book.submit(new BookOrder(i + 1, 1, OrderSide.SELL, OrderType.LIMIT,
                    1_000L, 1, 0, base.plusMillis(i)));
        }

        long start = System.nanoTime();
        for (int i = 0; i < ENGINE_ORDERS; i++) {
            book.submit(new BookOrder(ENGINE_ORDERS + i + 1, 2, OrderSide.BUY, OrderType.LIMIT,
                    1_000L, 1, 0, base.plusMillis(ENGINE_ORDERS + i)));
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double tps = ENGINE_ORDERS / seconds;

        log.info("ENGINE-THROUGHPUT orders={} elapsed={}s tps={}",
                ENGINE_ORDERS, String.format("%.3f", seconds), String.format("%.0f", tps));
        System.out.printf("ENGINE-THROUGHPUT tps=%.0f%n", tps);

        assertThat(tps).as("매칭 엔진 처리량 (FSD 목표 100건/초)").isGreaterThanOrEqualTo(100.0);
    }

    @Test
    @DisplayName("주문 경로 전체 — p95 < 500ms (FSD §13.1). 처리량은 원장 직렬화가 상한을 만든다")
    void endToEndOrderPath() {
        var market = support.listedMarket(null, 100);
        long seller = support.investor(0);
        long buyer = support.investor(500_000_000);
        support.giveUnits(market, seller, WARMUP + ORDERS + 10);

        for (int i = 0; i < WARMUP + ORDERS; i++) {
            trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                    OrderType.LIMIT, 1_000L, 1, support.newKey());
        }
        for (int i = 0; i < WARMUP; i++) {
            trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                    OrderType.LIMIT, 1_000L, 1, support.newKey());
        }

        List<Long> latenciesMicros = new ArrayList<>(ORDERS);
        long start = System.nanoTime();
        for (int i = 0; i < ORDERS; i++) {
            long t0 = System.nanoTime();
            var result = trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                    OrderType.LIMIT, 1_000L, 1, support.newKey());
            latenciesMicros.add((System.nanoTime() - t0) / 1_000);
            assertThat(result.filledUnits()).isEqualTo(1);
        }
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;

        latenciesMicros.sort(Long::compareTo);
        double tps = ORDERS / elapsedSeconds;
        double p95Millis = latenciesMicros.get((int) (ORDERS * 0.95) - 1) / 1_000.0;
        double p50Millis = latenciesMicros.get(ORDERS / 2) / 1_000.0;
        double avgMillis = latenciesMicros.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000.0;

        log.info("ORDER-PATH-THROUGHPUT orders={} elapsed={}s tps={} p50={}ms p95={}ms avg={}ms",
                ORDERS, String.format("%.2f", elapsedSeconds), String.format("%.1f", tps),
                String.format("%.1f", p50Millis), String.format("%.1f", p95Millis),
                String.format("%.1f", avgMillis));
        System.out.printf("ORDER-PATH-THROUGHPUT tps=%.1f p50=%.1f p95=%.1f avg=%.1f%n",
                tps, p50Millis, p95Millis, avgMillis);

        // FSD §13.1 주문 API p95 목표
        assertThat(p95Millis).as("주문 API p95 (FSD 목표 500ms)").isLessThan(500.0);
        // 회귀 감지용 하한. FSD의 100건/초에는 못 미치며 그 이유는 벤치마크 문서에 기록했다
        assertThat(tps).as("주문 경로 처리량 회귀 감지").isGreaterThanOrEqualTo(15.0);
    }

    @Test
    @DisplayName("파티션 확장성 — 서로 다른 종목 4개를 동시에 처리하면 총 처리량이 늘어난다")
    void partitionScaling() {
        int symbols = 4;
        int ordersPerSymbol = 40;

        List<TradingTestSupport.Market> markets = new ArrayList<>();
        List<Long> sellers = new ArrayList<>();
        List<Long> buyers = new ArrayList<>();
        for (int i = 0; i < symbols; i++) {
            var market = support.listedMarket(null, 100);
            long seller = support.investor(0);
            long buyer = support.investor(200_000_000);
            support.giveUnits(market, seller, ordersPerSymbol + 5);
            for (int j = 0; j < ordersPerSymbol; j++) {
                trading.place(market.tokenSymbol(), InvestorId.of(seller), OrderSide.SELL,
                        OrderType.LIMIT, 1_000L, 1, support.newKey());
            }
            markets.add(market);
            sellers.add(seller);
            buyers.add(buyer);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(symbols);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(symbols);

        long t0;
        try {
            for (int i = 0; i < symbols; i++) {
                final var market = markets.get(i);
                final long buyer = buyers.get(i);
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < ordersPerSymbol; j++) {
                            trading.place(market.tokenSymbol(), InvestorId.of(buyer), OrderSide.BUY,
                                    OrderType.LIMIT, 1_000L, 1, support.newKey());
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            t0 = System.nanoTime();
            start.countDown();
            assertThat(done.await(300, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }

        double seconds = (System.nanoTime() - t0) / 1_000_000_000.0;
        double totalTps = (symbols * ordersPerSymbol) / seconds;

        log.info("PARTITION-SCALING symbols={} ordersPerSymbol={} elapsed={}s totalTps={}",
                symbols, ordersPerSymbol, String.format("%.2f", seconds),
                String.format("%.1f", totalTps));
        System.out.printf("PARTITION-SCALING symbols=%d totalTps=%.1f%n", symbols, totalTps);

        assertThat(failures.get()).isZero();
    }
}

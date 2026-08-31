package com.fracta.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.InvestorId;
import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.subscription.application.AllotmentStrategySelector;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.SubscriptionTestSupport;

/**
 * 동시성 3방식이 전부 통과해야 하는 공통 계약 (FSD §8.3).
 * 하위 클래스가 subscription.concurrency 프로퍼티로 전략을 고정한다.
 */
abstract class SubscriptionConcurrencyContractTest extends IntegrationTestBase {

    private static final int THREADS = 50;
    private static final long TOTAL_UNITS = 100;
    private static final long UNITS_PER_REQUEST = 5;

    @Autowired
    SubscriptionService subscriptions;

    @Autowired
    SubscriptionTestSupport support;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    AllotmentStrategySelector selector;

    @Autowired
    JdbcTemplate jdbc;

    abstract String expectedStrategy();

    @Test
    @DisplayName("활성 전략이 설정과 일치한다")
    void activeStrategyMatches() {
        assertThat(selector.active().name()).isEqualTo(expectedStrategy());
    }

    @Test
    @DisplayName("50 스레드 동시 신청 → 초과 배정 0건, 재고 정확히 소진")
    void concurrentAppliesNeverOversell() throws Exception {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, TOTAL_UNITS, 100, 3);
        List<Long> investors = support.investors(THREADS, 3, 10_000);

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        java.util.Queue<String> unexpectedErrors = new java.util.concurrent.ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (long investorId : investors) {
                final long id = investorId;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        subscriptions.apply(ctx.issuanceId(), InvestorId.of(id),
                                UNITS_PER_REQUEST, UUID.randomUUID().toString());
                        success.incrementAndGet();
                    } catch (InsufficientUnitsException e) {
                        soldOut.incrementAndGet();      // 정상 거절
                    } catch (Exception e) {
                        unexpectedErrors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(60, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpectedErrors)
                .as("예상 밖 예외 (상위 5건): %s",
                        unexpectedErrors.stream().distinct().limit(5).toList())
                .isEmpty();
        assertThat(success.get()).isEqualTo((int) (TOTAL_UNITS / UNITS_PER_REQUEST));   // 정확히 20
        assertThat(soldOut.get()).isEqualTo(THREADS - success.get());

        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isZero();
        Long reserved = jdbc.queryForObject("""
                SELECT COALESCE(SUM(requested_units), 0) FROM subscription_order
                WHERE issuance_id = ? AND status = 'DEPOSITED'
                """, Long.class, ctx.issuanceId());
        assertThat(reserved).isEqualTo(TOTAL_UNITS);
    }

    @Test
    @DisplayName("신청 → 취소 → 재고·증거금 원복")
    void applyThenCancelRestores() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = support.investor(3, 5_000);

        var applied = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 7,
                UUID.randomUUID().toString());
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(93);

        subscriptions.cancel(applied.orderId(), InvestorId.of(investor));
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(100);
    }
}

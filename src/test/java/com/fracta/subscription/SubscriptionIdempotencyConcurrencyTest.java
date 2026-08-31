package com.fracta.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.SubscriptionTestSupport;

class SubscriptionIdempotencyConcurrencyTest extends IntegrationTestBase {

    private static final int THREADS = 100;

    @Autowired
    SubscriptionService subscriptions;

    @Autowired
    SubscriptionTestSupport support;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    AccountQueryPort accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("동일 키 동시 100건 → 실제 처리 1건, 나머지는 동일 응답 (증거금 1회만 차감)")
    void concurrentSameKeyProcessedOnce() throws Exception {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 1_000, 100, 3);
        // 증거금 500(5×100)이 정확히 1회만 차감 가능한 잔액 — 이중 처리 시 즉시 드러난다
        long investor = support.investor(3, 500);
        String sharedKey = "idem-" + System.nanoTime();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Set<Long> orderIds = ConcurrentHashMap.newKeySet();
        java.util.Queue<String> failures = new java.util.concurrent.ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(30);
        try {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        var result = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 5, sharedKey);
                        orderIds.add(result.orderId());
                    } catch (Exception e) {
                        failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("예상 밖 예외 (상위 5건): %s", failures.stream().distinct().limit(5).toList())
                .isEmpty();
        assertThat(orderIds).hasSize(1);   // 전원 동일한 최초 결과

        Long orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscription_order WHERE idempotency_key = ?", Long.class, sharedKey);
        assertThat(orderCount).isEqualTo(1);
        // 증거금 1회만 차감, 재고 1회만 감소
        assertThat(accounts.cashBalanceOf(InvestorId.of(investor)).amount()).isZero();
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(995);
    }
}

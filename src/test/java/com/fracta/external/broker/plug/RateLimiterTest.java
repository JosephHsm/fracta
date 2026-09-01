package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fracta.support.IntegrationTestBase;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 쿼터 두 구현의 계약 + 경계 버스트 차이 (FSD §9.3, phase-05 §5).
 * 이 차이가 "토큰버킷 → 슬라이딩 전환" 근거의 실체다.
 */
class RateLimiterTest extends IntegrationTestBase {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meterRegistry;

    private com.fracta.common.config.BrokerProperties props(int perSec, long maxWaitMillis) {
        return new com.fracta.common.config.BrokerProperties(
                "mock", "https://moapi.nhplug.com:8443", "https://api.nhplug.com:8443",
                "03", false, "k", "s",
                new com.fracta.common.config.BrokerProperties.Token(1800, "fracta:test:tok"),
                new com.fracta.common.config.BrokerProperties.RateLimit("sliding", perSec, maxWaitMillis),
                java.util.Map.of(), java.util.Set.of());
    }

    private SlidingWindowRateLimiter sliding(int perSec, long maxWait) {
        redis.delete("fracta:broker:quota:mock");
        // 증권사 쿼터도 공용 슬라이딩 카운터를 쓴다 (Phase 7에서 오픈 API와 구현을 합쳤다)
        return new SlidingWindowRateLimiter(
                new com.fracta.common.ratelimit.SlidingWindowCounter(redis),
                props(perSec, maxWait), meterRegistry);
    }

    @Test
    @DisplayName("슬라이딩: 초당 한도까지 허용하고 그 다음은 거절한다")
    void slidingAllowsUpToLimit() {
        var limiter = sliding(4, 0);
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("슬라이딩: 동시 100건에서도 설정 한도를 단 한 건도 초과하지 않는다")
    void slidingIsAtomicUnderConcurrency() throws Exception {
        var limiter = sliding(10, 0);
        int requests = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);
        AtomicInteger allowed = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(32);
        try {
            for (int i = 0; i < requests; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.tryAcquire()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed).hasValue(10);
    }

    @Test
    @DisplayName("슬라이딩: 경계 시점 버스트를 차단한다 — 0.9초에 4건 뒤 1.0초에 추가 요청 거절")
    void slidingBlocksBoundaryBurst() throws Exception {
        var limiter = sliding(4, 0);
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        Thread.sleep(300);   // 아직 1초 창 안 — 앞선 4건이 그대로 살아 있다
        assertThat(limiter.tryAcquire())
                .as("고정 윈도우였다면 여기서 창이 리셋돼 통과했을 지점")
                .isFalse();

        Thread.sleep(800);   // 창이 지나가면 다시 허용
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("토큰버킷: 조용한 구간 뒤 버스트를 허용한다 (슬라이딩과의 결정적 차이)")
    void bucketAllowsBurstAfterIdle() {
        var bucket = new TokenBucketRateLimiter(props(4, 0), meterRegistry);
        // 시작 시 용량만큼 차 있으므로 즉시 4건이 한꺼번에 나간다
        for (int i = 0; i < 4; i++) {
            assertThat(bucket.tryAcquire()).isTrue();
        }
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("한도 초과가 지속되면 대기 후 거절하고 fracta.broker.quota.rejected 가 증가한다")
    void rejectionIncrementsMetric() {
        var limiter = sliding(1, 100);
        assertThat(limiter.tryAcquire()).isTrue();

        double before = meterRegistry.get("fracta.broker.quota.rejected")
                .tag("strategy", "sliding").counter().count();

        assertThatThrownBy(() -> limiter.acquire("/krstock/quote/v1/currentPrice"))
                .isInstanceOf(BrokerApiException.class)
                .satisfies(e -> assertThat(((BrokerApiException) e).category())
                        .isEqualTo(BrokerApiException.Category.RATE_LIMIT));

        double after = meterRegistry.get("fracta.broker.quota.rejected")
                .tag("strategy", "sliding").counter().count();
        assertThat(after).isGreaterThan(before);
    }

    @Test
    @DisplayName("대기 여유가 있으면 창이 지난 뒤 통과시킨다 (거절이 아니라 대기)")
    void waitsInsteadOfRejectingWhenBudgetAllows() {
        var limiter = sliding(2, 2_000);
        limiter.acquire("/p");
        limiter.acquire("/p");
        long start = System.currentTimeMillis();
        limiter.acquire("/p");   // 창이 지날 때까지 대기 후 통과
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(500);
    }
}

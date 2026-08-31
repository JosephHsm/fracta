package com.fracta.external.broker.plug;

import org.springframework.stereotype.Component;

import com.fracta.common.config.BrokerProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 토큰버킷 — 비교용 대조군 (FSD §9.3의 "토큰버킷으로 시작 → 슬라이딩으로 전환" 서사).
 *
 * <p>용량만큼 토큰을 미리 쌓아두고 초당 {@code perSec} 만큼 채운다. 조용하던 구간 뒤에는
 * 쌓인 토큰이 한꺼번에 나가므로 <b>짧은 순간의 버스트를 허용</b>한다 — 이 점이
 * 슬라이딩 윈도우와의 결정적 차이이고, 서버 측 제한에 걸리는 원인이다.
 *
 * <p>인메모리라 인스턴스별로 따로 센다. 다중 인스턴스에서는 한도가 인스턴스 수만큼
 * 곱해지는 한계가 있다 — 이것도 슬라이딩(Redis)을 기본으로 택한 이유다.
 */
@Component
public class TokenBucketRateLimiter implements BrokerRateLimiter {

    private static final long POLL_INTERVAL_MILLIS = 20L;

    private final BrokerProperties properties;
    private final Counter rejected;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(BrokerProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.tokens = properties.rateLimit().perSec();
        this.lastRefillNanos = System.nanoTime();
        this.rejected = Counter.builder("fracta.broker.quota.rejected")
                .tag("strategy", name())
                .description("증권사 쿼터로 거절된 호출 수")
                .register(meterRegistry);
    }

    @Override
    public String name() {
        return "bucket";
    }

    @Override
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    @Override
    public void acquire(String path) {
        long deadline = System.nanoTime()
                + java.time.Duration.ofMillis(properties.rateLimit().maxWaitMillis()).toNanos();
        while (true) {
            if (tryAcquire()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                rejected.increment();
                throw BrokerApiException.rateLimited(path);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                rejected.increment();
                throw BrokerApiException.rateLimited(path);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        int capacity = properties.rateLimit().perSec();
        tokens = Math.min(capacity, tokens + elapsedSeconds * capacity);
        lastRefillNanos = now;
    }
}

package com.fracta.external.broker.plug;


import com.fracta.common.ratelimit.SlidingWindowCounter;
import org.springframework.stereotype.Component;

import com.fracta.common.config.BrokerProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 슬라이딩 윈도우 (Redis Sorted Set) — 기본 전략.
 *
 * <p><b>왜 이걸 기본으로 쓰는가</b>: 토큰버킷(및 고정 윈도우)은 창 경계에서 버스트가 난다.
 * 예를 들어 초당 4건 제한에서 0.9초에 4건, 1.1초에 4건을 보내면 0.2초 사이에 8건이 나간다.
 * 서버 측이 슬라이딩 방식으로 세면 이 버스트가 그대로 유량 초과(IGW42901~42903)로 걸린다.
 * 슬라이딩 윈도우는 "지금부터 과거 1초"를 매번 다시 세므로 경계 버스트가 생기지 않는다.
 *
 * <p>Redis를 쓰는 이유는 다중 인스턴스에서도 한도가 합산되어야 하기 때문이다.
 */
@Component
public class SlidingWindowRateLimiter implements BrokerRateLimiter {

    private static final long WINDOW_MILLIS = 1_000L;
    private static final long POLL_INTERVAL_MILLIS = 20L;

    private final SlidingWindowCounter counter;
    private final BrokerProperties properties;
    private final Counter rejected;

    public SlidingWindowRateLimiter(SlidingWindowCounter counter, BrokerProperties properties,
                                    MeterRegistry meterRegistry) {
        this.counter = counter;
        this.properties = properties;
        this.rejected = Counter.builder("fracta.broker.quota.rejected")
                .tag("strategy", name())
                .description("증권사 쿼터로 거절된 호출 수")
                .register(meterRegistry);
    }

    @Override
    public String name() {
        return "sliding";
    }

    @Override
    public boolean tryAcquire() {
        return counter.tryAcquire(key(), properties.rateLimit().perSec(),
                java.time.Duration.ofMillis(WINDOW_MILLIS)).allowed();
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

    private String key() {
        return "fracta:broker:quota:" + properties.env();
    }
}

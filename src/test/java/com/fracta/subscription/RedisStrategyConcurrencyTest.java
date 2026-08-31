package com.fracta.subscription;

import org.springframework.test.context.TestPropertySource;

/** 방식 B — Redis 분산락 (Redisson). */
@TestPropertySource(properties = "subscription.concurrency=redis")
class RedisStrategyConcurrencyTest extends SubscriptionConcurrencyContractTest {

    @Override
    String expectedStrategy() {
        return "redis";
    }
}

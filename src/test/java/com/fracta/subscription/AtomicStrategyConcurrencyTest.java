package com.fracta.subscription;

import org.springframework.test.context.TestPropertySource;

/** 방식 C — DB 원자적 감소 (기본값). */
@TestPropertySource(properties = "subscription.concurrency=atomic")
class AtomicStrategyConcurrencyTest extends SubscriptionConcurrencyContractTest {

    @Override
    String expectedStrategy() {
        return "atomic";
    }
}

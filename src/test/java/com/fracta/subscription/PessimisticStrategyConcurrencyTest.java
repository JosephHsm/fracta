package com.fracta.subscription;

import org.springframework.test.context.TestPropertySource;

/** 방식 A — 비관적 락. */
@TestPropertySource(properties = "subscription.concurrency=pessimistic")
class PessimisticStrategyConcurrencyTest extends SubscriptionConcurrencyContractTest {

    @Override
    String expectedStrategy() {
        return "pessimistic";
    }
}

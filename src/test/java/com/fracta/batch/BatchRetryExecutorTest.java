package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.batch.application.BatchRetryExecutor;

class BatchRetryExecutorTest {

    @Test
    @DisplayName("재시도 3회는 최초 시도 포함 총 4번이며 네 번째 성공을 반영한다")
    void retriesThreeTimesAfterInitialFailure() {
        AtomicInteger attempts = new AtomicInteger();
        new BatchRetryExecutor().run(3, () -> {
            if (attempts.incrementAndGet() < 4) {
                throw new RuntimeException("temporary failure");
            }
        });
        assertThat(attempts).hasValue(4);
    }
}

package com.fracta.batch.application;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/** 재시도 횟수를 "최초 1회 + N회 재시도" 의미로 고정한다. */
@Component
public class BatchRetryExecutor {

    public void run(int retries, Runnable action) {
        RetryTemplate.builder()
                .maxAttempts(Math.addExact(retries, 1))
                .retryOn(RuntimeException.class)
                .build()
                .execute(context -> {
                    action.run();
                    return null;
                });
    }
}

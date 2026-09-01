package com.fracta.openapi.log;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 호출 로그 전용 실행기. 로그 적재가 API 응답을 붙잡지 않도록 분리한다. */
@Configuration
@EnableAsync
public class ApiCallLogExecutorConfig {

    @Bean("apiCallLogExecutor")
    Executor apiCallLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("api-call-log-");
        // 큐가 넘치면 호출 스레드에서 실행하지 않고 버린다 — 로그 때문에 API가 느려지면 안 된다
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}

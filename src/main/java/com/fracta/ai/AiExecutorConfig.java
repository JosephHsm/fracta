package com.fracta.ai;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 인덱싱 전용 실행기.
 *
 * <p>동시성을 1~2로 묶는다. 임베딩은 CPU를 통째로 쓰는 작업이라 여러 건을 동시에
 * 돌리면 서로 느려지기만 한다. 큐가 넘치면 호출 스레드에서 실행한다 —
 * 인덱싱은 버리면 검색이 안 되므로 로그와 달리 폐기하지 않는다.
 */
@Configuration
public class AiExecutorConfig {

    @Bean("aiIndexExecutor")
    Executor aiIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-index-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

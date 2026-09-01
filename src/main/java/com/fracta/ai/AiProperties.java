package com.fracta.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 서비스 연동 설정 (FSD 부록 A).
 *
 * <p>모델 ID는 여기에 두지 않는다 — 모델 선택은 AI 서비스(Python)의 책임이고,
 * Core는 어떤 모델이 답했는지 응답으로 돌려받아 로그·메트릭에만 쓴다.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        /** AI 서비스 베이스 URL */
        String serviceUrl,
        /** 호출 타임아웃. AI가 느려도 본 서비스가 같이 느려지면 안 된다 */
        long timeoutMillis,
        CircuitBreaker circuitBreaker
) {

    /**
     * 서킷브레이커 설정.
     *
     * <p>resilience4j를 쓰지 않고 직접 구현한 이유: 사양에 없는 라이브러리를 늘리지 않기 위해서다.
     * 이 프로젝트는 이미 {@code SlidingWindowRateLimiter}·{@code TokenBucketRateLimiter}를
     * 자체 구현했고, 여기서 필요한 것도 "연속 실패 N회 → OPEN → 일정 시간 후 half-open" 수준이다.
     */
    public record CircuitBreaker(
            /** 연속 실패가 이 횟수에 도달하면 OPEN */
            int failureThreshold,
            /** OPEN 유지 시간. 지나면 half-open으로 1건 흘려본다 */
            long openMillis
    ) {
    }
}

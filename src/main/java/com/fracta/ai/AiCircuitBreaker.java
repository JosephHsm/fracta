package com.fracta.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 호출 전용 서킷브레이커.
 *
 * <p>AI 장애가 본 서비스를 막으면 안 된다 (phase-08 §2). 연속 실패가 임계치에 닿으면
 * OPEN으로 바꿔 즉시 실패시키고, {@code openMillis} 후 half-open 상태에서 1건만 흘려본다.
 *
 * <p>상태 전이:
 * <pre>
 * CLOSED --(연속 실패 N회)--> OPEN --(openMillis 경과)--> HALF_OPEN
 *   ^                                                        |
 *   +---------------------(성공)------------------------------+
 *                                (실패 시 다시 OPEN)
 * </pre>
 */
public class AiCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(AiCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong();
    private volatile State state = State.CLOSED;

    public AiCircuitBreaker(int failureThreshold, long openMillis) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold는 1 이상이어야 한다");
        }
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    /**
     * 회로가 열려 있으면 {@link AiUnavailableException}을 던지고 호출 자체를 하지 않는다.
     * 이게 서킷브레이커의 요점이다 — 죽은 서비스를 계속 두드려 스레드를 소모하지 않는다.
     */
    public <T> T execute(Supplier<T> call) {
        if (!allowRequest()) {
            throw new AiUnavailableException("AI 서비스 회로가 열려 있다 (연속 실패 "
                    + consecutiveFailures.get() + "회)");
        }
        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    private boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= openMillis) {
                state = State.HALF_OPEN;   // 탐색 호출 1건을 허용한다
                log.info("AI 서킷브레이커 HALF_OPEN — 탐색 호출을 1건 흘린다");
                return true;
            }
            return false;
        }
        // HALF_OPEN: 탐색 호출이 아직 결과를 내지 않았다. 추가 호출은 막는다
        return false;
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        if (state != State.CLOSED) {
            log.info("AI 서킷브레이커 CLOSED 복귀");
            state = State.CLOSED;
        }
    }

    private void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (state == State.HALF_OPEN || failures >= failureThreshold) {
            if (state != State.OPEN) {
                log.warn("AI 서킷브레이커 OPEN — 연속 실패 {}회, {}ms 동안 차단한다",
                        failures, openMillis);
            }
            state = State.OPEN;
            openedAt.set(System.currentTimeMillis());
        }
    }

    public State state() {
        // OPEN 유지 시간이 지났으면 조회 시점에도 HALF_OPEN으로 보이게 한다
        if (state == State.OPEN && System.currentTimeMillis() - openedAt.get() >= openMillis) {
            return State.HALF_OPEN;
        }
        return state;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}

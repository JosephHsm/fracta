package com.fracta.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 서킷브레이커 단위 테스트.
 *
 * <p>핵심은 "회로가 열리면 호출 자체를 하지 않는다"는 것이다. 예외만 바꿔 던지고
 * 실제로는 계속 두드린다면 서킷브레이커가 아니다 — 호출 횟수로 검증한다.
 */
class AiCircuitBreakerTest {

    @Test
    @DisplayName("연속 실패가 임계치에 닿으면 OPEN 되고 이후 호출은 실행되지 않는다")
    void opensAfterConsecutiveFailuresAndStopsCalling() {
        AiCircuitBreaker breaker = new AiCircuitBreaker(3, 30_000);
        AtomicInteger invocations = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> {
                invocations.incrementAndGet();
                throw new AiUnavailableException("down");
            })).isInstanceOf(AiUnavailableException.class);
        }

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.OPEN);
        assertThat(invocations.get()).isEqualTo(3);

        // 회로가 열린 뒤에는 공급자가 호출되지 않는다
        assertThatThrownBy(() -> breaker.execute(() -> {
            invocations.incrementAndGet();
            return "never";
        })).isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("회로가 열려");

        assertThat(invocations.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("임계치 직전까지의 실패로는 열리지 않는다")
    void staysClosedBelowThreshold() {
        AiCircuitBreaker breaker = new AiCircuitBreaker(3, 30_000);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> {
                throw new AiUnavailableException("down");
            })).isInstanceOf(AiUnavailableException.class);
        }

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
        assertThat(breaker.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("성공하면 연속 실패 카운터가 초기화된다")
    void successResetsFailureCount() {
        AiCircuitBreaker breaker = new AiCircuitBreaker(3, 30_000);

        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new AiUnavailableException("down");
        })).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new AiUnavailableException("down");
        })).isInstanceOf(AiUnavailableException.class);

        breaker.execute(() -> "ok");

        assertThat(breaker.consecutiveFailures()).isZero();
        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("OPEN 유지 시간이 지나면 HALF_OPEN 으로 탐색 호출 1건을 흘린다")
    void halfOpensAfterCooldown() throws InterruptedException {
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, 50);
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> breaker.execute(() -> {
            invocations.incrementAndGet();
            throw new AiUnavailableException("down");
        })).isInstanceOf(AiUnavailableException.class);
        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.OPEN);

        Thread.sleep(80);

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.execute(() -> {
            invocations.incrementAndGet();
            return "recovered";
        })).isEqualTo("recovered");

        assertThat(invocations.get()).isEqualTo(2);
        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HALF_OPEN 탐색 호출이 실패하면 즉시 다시 OPEN 된다")
    void reopensWhenProbeFails() throws InterruptedException {
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, 50);

        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new AiUnavailableException("down");
        })).isInstanceOf(AiUnavailableException.class);
        Thread.sleep(80);

        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new AiUnavailableException("still down");
        })).isInstanceOf(AiUnavailableException.class).hasMessage("still down");

        // 탐색이 실패했으니 쿨다운이 다시 시작된다
        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("임계치를 0 이하로 설정할 수 없다")
    void rejectsInvalidThreshold() {
        assertThatThrownBy(() -> new AiCircuitBreaker(0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

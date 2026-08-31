package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.config.BrokerProperties;

/** 설정 스위치로 쿼터 전략이 실제로 바뀌는지 (FSD §9.2 "두 구현 모두 유지하고 설정으로 스위치"). */
class RateLimiterSelectorTest {

    private static BrokerProperties props(String strategy) {
        return new BrokerProperties("mock", "https://moapi.nhplug.com:8443",
                "https://api.nhplug.com:8443", "03", false, "k", "s",
                new BrokerProperties.Token(1800, "p"),
                new BrokerProperties.RateLimit(strategy, 1, 0),
                java.util.Map.of(), java.util.Set.of());
    }

    private record StubLimiter(String name) implements BrokerRateLimiter {
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void acquire(String path) {
        }
    }

    private final List<BrokerRateLimiter> limiters =
            List.of(new StubLimiter("sliding"), new StubLimiter("bucket"));

    @Test
    @DisplayName("sliding 설정 → 슬라이딩 윈도우가 활성화된다 (기본값)")
    void selectsSliding() {
        assertThat(new RateLimiterSelector(limiters, props("sliding")).active().name())
                .isEqualTo("sliding");
    }

    @Test
    @DisplayName("bucket 설정 → 토큰버킷이 활성화된다")
    void selectsBucket() {
        assertThat(new RateLimiterSelector(limiters, props("bucket")).active().name())
                .isEqualTo("bucket");
    }

    @Test
    @DisplayName("알 수 없는 전략은 부팅을 실패시킨다 — 조용히 기본값으로 넘어가지 않는다")
    void rejectsUnknownStrategy() {
        assertThatThrownBy(() -> new RateLimiterSelector(limiters, props("magic")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("magic");
    }
}

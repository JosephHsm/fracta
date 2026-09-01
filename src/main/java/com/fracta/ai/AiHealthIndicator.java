package com.fracta.ai;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * AI 서비스 상태를 /actuator/health 에 노출한다.
 *
 * <p>회로가 열려 있어도 <b>UP</b>으로 보고한다. AI는 부가 기능이고, 여기서 DOWN을 내면
 * 헬스체크에 붙은 오케스트레이터가 본 서비스를 죽인다 — AI 장애가 본 서비스를 막으면
 * 안 된다는 요구사항과 정면으로 어긋난다. 상태는 detail 로만 드러낸다.
 */
@Component("aiService")
public class AiHealthIndicator implements HealthIndicator {

    private final AiHttpAdapter adapter;

    public AiHealthIndicator(AiHttpAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Health health() {
        AiCircuitBreaker.State state = adapter.circuitState();
        return Health.up()
                .withDetail("circuit", state.name())
                .withDetail("available", state != AiCircuitBreaker.State.OPEN)
                .build();
    }
}

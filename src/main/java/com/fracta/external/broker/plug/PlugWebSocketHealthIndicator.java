package com.fracta.external.broker.plug;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** WebSocket 연결 상태를 /actuator/health 에 노출한다 (phase-05 §3.4). */
@Component("brokerWebSocket")
@Profile("plug")
public class PlugWebSocketHealthIndicator implements HealthIndicator {

    private final PlugWebSocketClient client;
    private final MarketHours marketHours;

    public PlugWebSocketHealthIndicator(PlugWebSocketClient client, MarketHours marketHours) {
        this.client = client;
        this.marketHours = marketHours;
    }

    /**
     * 구독이 있는데 끊겼을 때만 DOWN이다.
     *
     * <p>예전에는 "장중인데 미연결"이면 DOWN이었다. 그런데 이 클라이언트는 자동 연결하지 않는다 —
     * 구독이 생길 때 붙는다. 시세를 REST로만 쓰는 구성에서는 미연결이 <b>정상</b>인데도
     * 앱 전체 health가 DOWN이 되어 컨테이너가 unhealthy로 떨어졌다(실제로 그랬다).
     *
     * <p>구독이 없다는 건 "이 기능을 안 쓰는 중"이지 고장이 아니다.
     */
    @Override
    public Health health() {
        boolean connected = client.isConnected();
        boolean inUse = !client.subscribedTickers().isEmpty();
        boolean faulty = inUse && !connected && marketHours.isOpen();
        Health.Builder builder = faulty ? Health.down() : Health.up();
        return builder
                .withDetail("inUse", inUse)
                .withDetail("connected", connected)
                .withDetail("marketOpen", marketHours.isOpen())
                .withDetail("subscriptions", client.subscribedTickers().size())
                .withDetail("reconnectAttempts", client.reconnectAttempts())
                .build();
    }
}

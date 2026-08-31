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

    @Override
    public Health health() {
        boolean connected = client.isConnected();
        Health.Builder builder = connected || !marketHours.isOpen() ? Health.up() : Health.down();
        return builder
                .withDetail("connected", connected)
                .withDetail("marketOpen", marketHours.isOpen())
                .withDetail("subscriptions", client.subscribedTickers().size())
                .withDetail("reconnectAttempts", client.reconnectAttempts())
                .build();
    }
}

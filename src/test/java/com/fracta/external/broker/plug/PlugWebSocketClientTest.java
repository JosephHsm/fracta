package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.common.config.BrokerProperties;

/** WebSocket 재연결 백오프·구독 복원·서버 한도 (phase-05 §3.4). */
class PlugWebSocketClientTest {

    private static BrokerProperties props(String env, String baseUrl) {
        return new BrokerProperties(env, baseUrl, "https://api.nhplug.com:8443", "03", false,
                "key", "secret",
                new BrokerProperties.Token(1800, "p"),
                new BrokerProperties.RateLimit("sliding", 1, 1000),
                java.util.Map.of(), java.util.Set.of());
    }

    private PlugWebSocketClient client(String env, String baseUrl, MarketHours hours) {
        PlugTokenManager tokenManager = mock(PlugTokenManager.class);
        when(tokenManager.accessToken()).thenReturn("TEST-TOKEN");
        return new PlugWebSocketClient(props(env, baseUrl), tokenManager, hours,
                mock(TaskScheduler.class), new ObjectMapper());
    }

    private static MarketHours openMarket() {
        return new MarketHours(java.time.Clock.fixed(
                java.time.LocalDateTime.parse("2026-08-31T10:00:00").atZone(MarketHours.KST).toInstant(),
                MarketHours.KST));
    }

    private static MarketHours closedMarket() {
        return new MarketHours(java.time.Clock.fixed(
                java.time.LocalDateTime.parse("2026-08-31T20:00:00").atZone(MarketHours.KST).toInstant(),
                MarketHours.KST));
    }

    @Test
    @DisplayName("모의투자는 17070, 운영 국내 시세는 7070. 경로 /websocket 필수")
    void webSocketUrl() {
        assertThat(client("mock", "https://moapi.nhplug.com:8443", openMarket()).webSocketUrl())
                .isEqualTo("wss://moapi.nhplug.com:17070/websocket");
        assertThat(client("live", "https://api.nhplug.com:8443", openMarket()).webSocketUrl())
                .isEqualTo("wss://api.nhplug.com:7070/websocket");
    }

    @Test
    @DisplayName("재연결 백오프는 1→2→4→…→60초 상한으로 증가한다")
    void exponentialBackoffWithCeiling() {
        var ws = client("mock", "https://moapi.nhplug.com:8443", openMarket());
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            seconds.add(ws.nextBackoff().getSeconds());
        }
        assertThat(seconds.subList(0, 7)).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L);
        // 상한 고정 — 무한 증가하지 않는다
        assertThat(seconds).allMatch(s -> s <= Duration.ofSeconds(60).getSeconds());
        assertThat(seconds.get(9)).isEqualTo(60L);
    }

    @Test
    @DisplayName("재연결 후 구독 목록을 복원한다 — 빠뜨리면 조용히 시세가 멈춘다")
    void restoresSubscriptionsAfterReconnect() throws Exception {
        var ws = client("mock", "https://moapi.nhplug.com:8443", closedMarket());
        // 장 시간 외에는 등록만 하고 전송하지 않는다
        ws.subscribe("005930", tick -> { });
        ws.subscribe("000660", tick -> { });
        assertThat(ws.subscribedTickers()).containsExactlyInAnyOrder("005930", "000660");

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        java.lang.reflect.Field field = PlugWebSocketClient.class.getDeclaredField("session");
        field.setAccessible(true);
        field.set(ws, session);

        ws.restoreSubscriptions();

        // 구독 2건이 모두 재전송된다
        verify(session, org.mockito.Mockito.times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("세션당 실시간 등록 한도(10)를 넘기면 거부한다 — 넘기면 서버가 조용히 끊는다")
    void enforcesSubscriptionLimit() {
        var ws = client("mock", "https://moapi.nhplug.com:8443", closedMarket());
        for (int i = 0; i < PlugWebSocketClient.MAX_SUBSCRIPTIONS_PER_SESSION; i++) {
            ws.subscribe("T%05d".formatted(i), tick -> { });
        }
        assertThat(ws.subscribedTickers()).hasSize(10);

        assertThatThrownBy(() -> ws.subscribe("OVER1", tick -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("한도");

        // 이미 구독 중인 종목의 재구독은 한도와 무관하게 허용된다
        ws.subscribe("T00000", tick -> { });
    }

    @Test
    @DisplayName("장 시간 외에는 구독을 등록만 하고 전송하지 않는다")
    void doesNotSendOutsideMarketHours() {
        var ws = client("mock", "https://moapi.nhplug.com:8443", closedMarket());
        ws.subscribe("005930", tick -> { });
        assertThat(ws.subscribedTickers()).contains("005930");
        assertThat(ws.isConnected()).isFalse();
    }
}

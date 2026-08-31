package com.fracta.external.broker.plug;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.common.config.BrokerProperties;
import com.fracta.common.money.Money;
import com.fracta.external.broker.Tick;

/**
 * PLUG 실시간 시세 WebSocket.
 *
 * <p>접속·구독 규약 (공식 {@code docs/realtime_channels.md}):
 * <pre>
 * wss://{host}:{port}/websocket     경로 /websocket 필수. 모의투자 포트 17070
 * {"header":{"token":"&lt;access_token&gt;","tr_type":"1"},"body":{"tr_cd":"mc","tr_key":"005930"}}
 * tr_type  1=등록 2=해제.  토큰은 header.token 으로만 보낸다 (Authorization 미사용)
 * </pre>
 *
 * <p>서버 한도: 앱키당 동시 세션 2, 세션당 등록 10, 구독 전송 초당 10건.
 * 세션당 10건을 넘기면 오류 메시지 없이 close 1000 "Bye" 로 끊기므로 등록 수를 직접 막는다.
 *
 * <p>재연결은 지수 백오프(1s→2s→4s→…→60s 상한)이고, 재연결 후 구독 목록을 자동 복원한다.
 * 복원을 빠뜨리면 조용히 시세가 멈춘다.
 */
@Component
@Profile("plug")
public class PlugWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(PlugWebSocketClient.class);

    static final int MAX_SUBSCRIPTIONS_PER_SESSION = 10;
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final BrokerProperties properties;
    private final PlugTokenManager tokenManager;
    private final MarketHours marketHours;
    private final TaskScheduler scheduler;
    private final ObjectMapper objectMapper;

    private final Map<String, Consumer<Tick>> handlers = new ConcurrentHashMap<>();
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private volatile WebSocketSession session;
    private volatile boolean shuttingDown;

    public PlugWebSocketClient(BrokerProperties properties, PlugTokenManager tokenManager,
                               MarketHours marketHours, TaskScheduler scheduler,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.marketHours = marketHours;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    public boolean isConnected() {
        WebSocketSession current = session;
        return current != null && current.isOpen();
    }

    public Set<String> subscribedTickers() {
        return Set.copyOf(subscriptions);
    }

    public int reconnectAttempts() {
        return reconnectAttempts.get();
    }

    public synchronized void subscribe(String ticker, Consumer<Tick> handler) {
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS_PER_SESSION && !subscriptions.contains(ticker)) {
            throw new IllegalStateException(
                    "세션당 실시간 등록 한도(%d)를 초과했다".formatted(MAX_SUBSCRIPTIONS_PER_SESSION));
        }
        handlers.put(ticker, handler);
        subscriptions.add(ticker);

        if (!marketHours.isOpen()) {
            log.info("장 시간 외 — 구독만 등록하고 전송은 개장 후로 미룬다: {}", ticker);
            return;
        }
        ensureConnected();
        send(ticker, "1");
    }

    public synchronized void unsubscribe(String ticker) {
        subscriptions.remove(ticker);
        handlers.remove(ticker);
        if (isConnected()) {
            send(ticker, "2");
        }
    }

    /** 재연결 후 구독 복원. 이걸 빠뜨리면 조용히 시세가 멈춘다. */
    void restoreSubscriptions() {
        for (String ticker : subscriptions) {
            send(ticker, "1");
        }
        log.info("WebSocket 구독 복원 완료: {}건", subscriptions.size());
    }

    private void send(String ticker, String trType) {
        WebSocketSession current = session;
        if (current == null || !current.isOpen()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "header", Map.of("token", tokenManager.accessToken(), "tr_type", trType),
                    "body", Map.of("tr_cd", channelCode(), "tr_key", ticker)));
            current.sendMessage(new TextMessage(payload));
        } catch (Exception e) {
            log.warn("WebSocket 구독 메시지 전송 실패: ticker={} trType={}", ticker, trType, e);
        }
    }

    /** KRX 체결가 채널. 통합(UNT)은 mc, KRX 전용은 oc. */
    private String channelCode() {
        return "oc";
    }

    synchronized void ensureConnected() {
        if (isConnected() || shuttingDown) {
            return;
        }
        try {
            StandardWebSocketClient client = new StandardWebSocketClient();
            session = client.execute(new Handler(), null, URI.create(webSocketUrl()))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            reconnectAttempts.set(0);
            log.info("WebSocket 연결 성공: {}", webSocketUrl());
        } catch (Exception e) {
            log.warn("WebSocket 연결 실패: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    String webSocketUrl() {
        // 모의투자는 17070(국내·해외 공통), 운영 국내 시세는 7070. 경로 /websocket 필수.
        String host = URI.create(properties.baseUrl()).getHost();
        int port = "mock".equals(properties.env()) ? 17070 : 7070;
        return "wss://%s:%d/websocket".formatted(host, port);
    }

    /** 지수 백오프 1s → 2s → 4s → … → 60s 상한. */
    Duration nextBackoff() {
        int attempt = reconnectAttempts.getAndIncrement();
        long seconds = INITIAL_BACKOFF.getSeconds() << Math.min(attempt, 6);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.getSeconds()));
    }

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        Duration delay = nextBackoff();
        log.info("WebSocket 재연결을 {}초 후 시도한다", delay.getSeconds());
        scheduler.schedule(this::ensureConnected, java.time.Instant.now().plus(delay));
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        shuttingDown = true;
        WebSocketSession current = session;
        if (current != null && current.isOpen()) {
            try {
                current.close(CloseStatus.NORMAL);
            } catch (Exception ignored) {
                // 종료 중 예외는 무시한다
            }
        }
    }

    private class Handler extends TextWebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession established) {
            session = established;
            restoreSubscriptions();
        }

        @Override
        protected void handleTextMessage(WebSocketSession webSocketSession, TextMessage message) {
            try {
                Map<?, ?> payload = objectMapper.readValue(message.getPayload(), Map.class);
                Object bodyValue = payload.get("body");
                if (!(bodyValue instanceof Map<?, ?> body)) {
                    return;
                }
                String ticker = String.valueOf(body.get("tr_key"));
                Consumer<Tick> handler = handlers.get(ticker);
                if (handler == null) {
                    return;
                }
                handler.accept(new Tick(ticker,
                        Money.of(parseLong(body.get("stck_prpr"))),
                        parseLong(body.get("cntg_vol")),
                        java.time.Instant.now()));
            } catch (Exception e) {
                log.warn("WebSocket 메시지 처리 실패", e);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession closed, CloseStatus status) {
            log.warn("WebSocket 연결 종료 (code={}, reason={})", status.getCode(), status.getReason());
            session = null;
            scheduleReconnect();
        }
    }

    private static long parseLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}

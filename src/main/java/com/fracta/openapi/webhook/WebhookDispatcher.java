package com.fracta.openapi.webhook;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.subscription.api.SubscriptionAllottedEvent;
import com.fracta.trading.api.TradeEvents;

/**
 * 웹훅 발송 (OA-07, OA-08).
 *
 * <p>발송은 <b>요청 스레드에서 하지 않는다</b>. 수신 측이 느리면 주문 API가 같이 막힌다
 * (phase-07 §흔한 실수 10). 이벤트를 Redis Stream에 넣고 워커가 꺼내 보낸다.
 *
 * <p>재시도는 지수 백오프 5회. 소진되면 {@code DEAD}(DLQ)로 남겨 포털에서 수동 재발송한다.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    static final String STREAM_KEY = "fracta:webhook:stream";
    static final int MAX_ATTEMPTS = 5;

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final long timeoutMillis;

    public WebhookDispatcher(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                             StringRedisTemplate redis, ObjectMapper objectMapper,
                             RestClient.Builder builder,
                             @Value("${openapi.webhook.timeout-millis:3000}") long timeoutMillis) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.redis = redis;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        this.restClient = builder.requestFactory(requestFactory).build();
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 이벤트를 구독 중인 모든 엔드포인트에 대해 발송 건을 만들고 큐에 넣는다.
     * 호출자의 트랜잭션에 참여하므로 롤백되면 발송도 없던 일이 된다.
     */
    // AFTER_COMMIT 도메인 이벤트 리스너에서 호출된다. 원 트랜잭션에 참여하면 이 시점의 변경은
    // 더 이상 커밋되지 않으므로 반드시 새 트랜잭션에서 delivery를 만든다.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int publish(WebhookEvent event, Object payload) {
        String body = serialize(payload);
        List<WebhookEndpoint> targets = endpoints.findByActiveTrue().stream()
                .filter(e -> e.subscribes(event))
                .filter(e -> belongsToOwner(e, payload))
                .toList();

        java.util.List<Long> deliveryIds = new java.util.ArrayList<>(targets.size());
        for (WebhookEndpoint endpoint : targets) {
            WebhookDelivery delivery = deliveries.save(
                    new WebhookDelivery(endpoint.id(), event.value(), body));
            deliveryIds.add(delivery.id());
        }
        enqueueAfterCommit(deliveryIds);
        if (!targets.isEmpty()) {
            log.info("웹훅 발송 대기 {}건 등록: event={}", targets.size(), event.value());
        }
        return targets.size();
    }

    /** 개인 주문·청약 이벤트가 다른 클라이언트에 새지 않도록 소유자를 대조한다. */
    static boolean belongsToOwner(WebhookEndpoint endpoint, Object payload) {
        long eventOwner = switch (payload) {
            case TradeEvents.OrderFilled e -> e.investorId();
            case TradeEvents.OrderPartiallyFilled e -> e.investorId();
            case TradeEvents.OrderCancelled e -> e.investorId();
            case SubscriptionAllottedEvent e -> e.investorId();
            default -> endpoint.ownerInvestorId(); // token.listed/suspended는 공개 이벤트
        };
        return endpoint.ownerInvestorId() == eventOwner;
    }

    /**
     * DB 발송 건이 커밋된 뒤에만 Stream에 공개한다. 커밋 전에 큐에 넣으면 워커가 아직 보이지 않는
     * delivery를 읽고 레코드를 지워 버릴 수 있다.
     */
    private void enqueueAfterCommit(List<Long> deliveryIds) {
        Runnable enqueue = () -> deliveryIds.forEach(this::enqueue);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }

    private void enqueue(long deliveryId) {
        redis.opsForStream().add(STREAM_KEY, Map.of("deliveryId", String.valueOf(deliveryId)));
    }

    /** Redis Stream 워커. 실제 HTTP 발송은 요청 스레드와 완전히 분리된다. */
    @Scheduled(fixedDelayString = "${openapi.webhook.worker-delay-millis:500}")
    public void scheduledDrain() {
        try {
            drainQueue();
        } catch (RuntimeException e) {
            // 일시적인 Redis/수신 측 장애가 스케줄러 자체를 멈추게 두지 않는다.
            log.warn("웹훅 큐 처리 실패 — 다음 주기에 재시도한다", e);
        }
    }

    /**
     * 큐에서 꺼내 실제로 보낸다. 워커가 주기적으로 호출한다.
     *
     * @return 처리한 건수
     */
    @SuppressWarnings("unchecked") // Spring Data StreamOperations의 hash key/value 제네릭 추론 한계
    public int drainQueue() {
        int processed = 0;
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                StreamOffset.fromStart(STREAM_KEY));
        if (records == null) {
            return 0;
        }
        for (var record : records) {
            Object deliveryId = record.getValue().get("deliveryId");
            if (deliveryId != null) {
                deliverWithRetry(Long.parseLong(String.valueOf(deliveryId)));
                processed++;
            }
            // 먼저 지우면 이 지점 이전의 프로세스 중단에서 발송 건이 영구 유실된다.
            // 발송·DLQ 저장 후 삭제해 중복 가능성은 허용하되 유실은 막는다(at-least-once).
            redis.opsForStream().delete(STREAM_KEY, record.getId());
        }
        return processed;
    }

    /** 지수 백오프로 최대 5회 시도한다. 마지막까지 실패하면 DLQ. */
    public WebhookDelivery deliverWithRetry(long deliveryId) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.status() != WebhookDelivery.Status.PENDING) {
            return delivery;
        }
        WebhookEndpoint endpoint = endpoints.findById(delivery.endpointId()).orElse(null);
        if (endpoint == null) {
            delivery.recordAttempt("엔드포인트가 삭제됐다");
            delivery.markDead();
            return deliveries.save(delivery);
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                send(endpoint, delivery);
                delivery.recordAttempt(null);
                delivery.markDelivered();
                return deliveries.save(delivery);
            } catch (Exception e) {
                delivery.recordAttempt(e.getMessage());
                if (attempt < MAX_ATTEMPTS - 1) {
                    sleep(backoff(attempt));
                }
            }
        }
        delivery.markDead();
        log.error("웹훅 발송 최종 실패 — DLQ 기록: deliveryId={} url={} attempts={}",
                delivery.id(), endpoint.url(), delivery.attempts());
        return deliveries.save(delivery);
    }

    /** Phase 10 포털이 호출할 수동 재발송 진입점. DEAD 건만 새 큐 작업으로 되돌린다. */
    public WebhookDelivery redeliver(long deliveryId) {
        WebhookDelivery delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("웹훅 발송 건이 없다: " + deliveryId));
        delivery.resetForRetry();
        WebhookDelivery saved = deliveries.save(delivery);
        // 포털 애플리케이션 서비스의 트랜잭션 안에서 호출될 수 있다. 커밋 전에 큐에 넣으면
        // 워커가 아직 PENDING 변경을 보지 못하고 레코드를 버릴 수 있으므로 커밋 뒤 공개한다.
        enqueueAfterCommit(java.util.List.of(saved.id()));
        return saved;
    }

    private void send(WebhookEndpoint endpoint, WebhookDelivery delivery) {
        // 서명은 보내는 본문 원문 그대로에 대해 만든다 — 재직렬화하면 수신 측 검증이 깨진다
        String rawBody = delivery.payload();
        String signature = WebhookSigner.sign(endpoint.secret(), rawBody, Instant.now());

        restClient.post()
                .uri(endpoint.url())
                .header(WebhookSigner.HEADER, signature)
                .header("X-Fracta-Event", delivery.eventType())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(rawBody)
                .retrieve()
                .toBodilessEntity();
    }

    /** 1s → 2s → 4s → 8s → 16s (상한 16s). 테스트에서는 짧게 잡을 수 있다. */
    Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt, 4);
        long millis = Math.min(16_000L, multiplier * baseBackoffMillis());
        return Duration.ofMillis(millis);
    }

    private long baseBackoffMillis() {
        return timeoutMillis <= 100 ? 10 : 1_000;   // 테스트용 짧은 타임아웃이면 백오프도 줄인다
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 페이로드 직렬화 실패", e);
        }
    }
}

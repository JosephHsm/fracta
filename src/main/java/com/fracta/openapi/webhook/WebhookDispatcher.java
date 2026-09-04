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
import com.fracta.issuance.api.TokenListedEvent;
import com.fracta.issuance.api.TokenSuspendedEvent;
import com.fracta.subscription.api.SubscriptionAllottedEvent;
import com.fracta.trading.api.TradeEvents;

/**
 * 웹훅 발송 (OA-07, OA-08).
 *
 * <p>발송은 <b>요청 스레드에서 하지 않는다</b>. 수신 측이 느리면 주문 API가 같이 막힌다
 * (phase-07 §흔한 실수 10). 이벤트를 Redis Stream에 넣고 워커가 꺼내 보낸다.
 *
 * <p>재시도는 지수 백오프 5회. 소진되면 {@code DEAD}(DLQ)로 남겨 포털에서 수동 재발송한다.
 *
 * <p><b>재시도를 워커 스레드에서 기다리지 않는다.</b> 한 번 시도하고 실패하면 다음 시도 시각만
 * 적어 두고 곧장 다음 건으로 넘어간다. 예전에는 실패할 때마다 그 자리에서 1→2→4→8초를
 * {@code sleep} 했는데, 큐를 한 틱에 순차 처리하므로 죽은 엔드포인트 하나가 최대 15초씩
 * 잡아먹으며 다른 모든 클라이언트의 웹훅을 줄세웠다 — 수신 측 지연이 발송을 막지 않게 하려고
 * 큐를 둔 건데 워커가 같은 방식으로 막히고 있었다.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    static final String STREAM_KEY = "fracta:webhook:stream";
    static final int MAX_ATTEMPTS = 5;

    /** 한 주기에 집어 오는 재시도 건수 상한. 밀린 걸 한 번에 다 처리하려 들지 않는다. */
    static final int RETRY_SWEEP_LIMIT = 100;

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

    /**
     * 개인 주문·청약 이벤트가 다른 클라이언트에 새지 않도록 소유자를 대조한다.
     *
     * <p><b>모르는 이벤트는 내보내지 않는다.</b> 예전에는 {@code default} 가 엔드포인트 소유자를
     * 그대로 돌려줘 항상 참이 됐다. 공개 이벤트를 통과시키려는 의도였지만, 새 개인 이벤트를
     * 추가하면서 {@code case} 를 빠뜨리면 그 이벤트가 조용히 전체 공개가 된다.
     * 공개 목록을 명시하고 그 밖은 막는다.
     */
    static boolean belongsToOwner(WebhookEndpoint endpoint, Object payload) {
        Long eventOwner = privateEventOwner(payload);
        if (eventOwner != null) {
            return endpoint.ownerInvestorId() == eventOwner;
        }
        if (isPublicEvent(payload)) {
            return true;
        }
        // 개인 것도 공개 것도 아니라고 판정되면 보내지 않는다 — 분류를 빠뜨린 새 이벤트다
        log.error("분류되지 않은 웹훅 페이로드라 발송하지 않는다: {}",
                payload == null ? "null" : payload.getClass().getName());
        return false;
    }

    /** 개인 이벤트면 그 주인의 투자자 ID, 아니면 null. */
    private static Long privateEventOwner(Object payload) {
        return switch (payload) {
            // 패턴 switch 는 null 셀렉터에서 NPE 를 던진다. 명시적으로 받아 미분류로 넘긴다.
            case null -> null;
            case TradeEvents.OrderFilled e -> e.investorId();
            case TradeEvents.OrderPartiallyFilled e -> e.investorId();
            case TradeEvents.OrderCancelled e -> e.investorId();
            case SubscriptionAllottedEvent e -> e.investorId();
            default -> null;
        };
    }

    /** 모든 구독자에게 나가도 되는 시장 공지. 여기 없는 것은 공개가 아니다. */
    private static boolean isPublicEvent(Object payload) {
        return payload instanceof TokenListedEvent || payload instanceof TokenSuspendedEvent;
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
        // 스트림이 비어도 재시도 스윕은 돌아야 한다 — 여기서 돌아가면 예약된 재시도가 영영 멈춘다
        if (records == null) {
            records = List.of();
        }
        for (var record : records) {
            Object deliveryId = record.getValue().get("deliveryId");
            if (deliveryId != null) {
                attemptDelivery(Long.parseLong(String.valueOf(deliveryId)));
                processed++;
            }
            // 먼저 지우면 이 지점 이전의 프로세스 중단에서 발송 건이 영구 유실된다.
            // 발송·DLQ 저장 후 삭제해 중복 가능성은 허용하되 유실은 막는다(at-least-once).
            redis.opsForStream().delete(STREAM_KEY, record.getId());
        }
        return processed + sweepDueRetries();
    }

    /**
     * 시도 시각이 지난 재시도 건을 집어 한 번씩 시도한다.
     *
     * <p>스트림은 새 발송을 즉시 알리는 용도이고, 재시도 대기는 DB의 {@code next_attempt_at} 이
     * 들고 있다. 실패 건이 워커를 붙잡지 않으므로 엔드포인트 하나가 죽어도 나머지는 계속 나간다.
     *
     * @return 시도한 건수
     */
    public int sweepDueRetries() {
        List<WebhookDelivery> due = deliveries
                .findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        WebhookDelivery.Status.PENDING, Instant.now(),
                        org.springframework.data.domain.Limit.of(RETRY_SWEEP_LIMIT));
        for (WebhookDelivery delivery : due) {
            attemptDelivery(delivery.id());
        }
        return due.size();
    }

    /**
     * <b>한 번만</b> 시도한다. 실패하면 다음 시도 시각을 지수 백오프로 예약하고 곧장 돌아간다.
     * 5회를 소진하면 DLQ({@code DEAD}).
     *
     * <p>워커 스레드에서 재시도를 기다리지 않는 것이 핵심이다. 기다리면 죽은 엔드포인트 하나가
     * 큐 전체를 붙잡아, 큐를 둔 이유 자체가 사라진다.
     *
     * @return 갱신된 발송 건. 대상이 없거나 이미 끝난 건이면 그대로 돌려준다
     */
    public WebhookDelivery attemptDelivery(long deliveryId) {
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

        try {
            send(endpoint, delivery);
            delivery.recordAttempt(null);
            delivery.markDelivered();
            return deliveries.save(delivery);
        } catch (Exception e) {
            delivery.recordAttempt(e.getMessage());
            if (delivery.attempts() >= MAX_ATTEMPTS) {
                delivery.markDead();
                log.error("웹훅 발송 최종 실패 — DLQ 기록: deliveryId={} url={} attempts={}",
                        delivery.id(), endpoint.url(), delivery.attempts());
            } else {
                Duration wait = backoff(delivery.attempts() - 1);
                delivery.scheduleRetryAt(Instant.now().plus(wait));
                log.warn("웹훅 발송 실패 — {}ms 뒤 재시도 예약: deliveryId={} attempts={} 사유={}",
                        wait.toMillis(), delivery.id(), delivery.attempts(), e.getMessage());
            }
            return deliveries.save(delivery);
        }
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

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 페이로드 직렬화 실패", e);
        }
    }
}

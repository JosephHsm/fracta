package com.fracta.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * AI 서비스(FastAPI) HTTP 어댑터.
 *
 * <p>타임아웃 + 서킷브레이커가 필수다 (phase-08 §2). AI가 느리거나 죽어도 본 서비스의
 * 스레드를 잡아먹으면 안 된다.
 *
 * <p>{@code fracta.ai.guardrail.blocked} (FSD §13.3) 카운터는 여기서 올린다.
 * 가드레일 판정 자체는 AI 서비스가 하지만, 지표의 정본은 Micrometer 쪽이다.
 */
@Component
public class AiHttpAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(AiHttpAdapter.class);

    private final RestClient restClient;
    private final AiProperties properties;
    private final AiCircuitBreaker circuitBreaker;
    private final Counter guardrailBlocked;
    private final Counter callFailures;

    public AiHttpAdapter(AiProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.circuitBreaker = new AiCircuitBreaker(
                properties.circuitBreaker().failureThreshold(),
                properties.circuitBreaker().openMillis());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(properties.timeoutMillis(), 3000)));
        factory.setReadTimeout(Duration.ofMillis(properties.timeoutMillis()));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.serviceUrl())
                .build();

        this.guardrailBlocked = Counter.builder("fracta.ai.guardrail.blocked")
                .description("AI 가드레일이 차단한 응답 수")
                .register(meterRegistry);
        this.callFailures = Counter.builder("fracta.ai.call.failed")
                .description("AI 서비스 호출 실패 수 (타임아웃·5xx·회로 개방)")
                .register(meterRegistry);
    }

    @Override
    public ProspectusAnswer askProspectus(long issuanceId, String question, String requestId) {
        Map<String, Object> body = Map.of(
                "issuance_id", issuanceId,
                "question", question,
                "request_id", requestId == null ? "" : requestId);

        Map<String, Object> response = post("/ai/prospectus/ask", body);

        boolean blocked = asBoolean(response.get("blocked"));
        if (blocked) {
            guardrailBlocked.increment();
            // 사유 코드만 남긴다. 질문 원문은 AI 서비스의 ai_conversation_log 에 있다
            log.info("AI 가드레일 차단: reason={}, issuanceId={}",
                    response.get("blocked_reason"), issuanceId);
        }

        return new ProspectusAnswer(
                asString(response.get("answer")),
                asIntegerList(response.get("cited_pages")),
                blocked,
                asString(response.get("blocked_reason")),
                asBoolean(response.get("llm_called")),
                asDouble(response.get("top_similarity")),
                asString(response.get("model_id")),
                asString(response.get("provider")));
    }

    @Override
    public DevPortalAnswer askDevPortal(String question, String requestId) {
        Map<String, Object> body = Map.of(
                "question", question,
                "request_id", requestId == null ? "" : requestId);

        Map<String, Object> response = post("/ai/devportal/ask", body);

        boolean blocked = asBoolean(response.get("blocked"));
        if (blocked) {
            guardrailBlocked.increment();
        }

        return new DevPortalAnswer(
                asString(response.get("answer")),
                asStringList(response.get("cited_endpoints")),
                blocked,
                asString(response.get("blocked_reason")),
                asBoolean(response.get("llm_called")),
                asString(response.get("model_id")),
                asString(response.get("provider")));
    }

    @Override
    public IndexResult indexProspectus(long issuanceId, String fileKey) {
        Map<String, Object> response = post("/ai/prospectus/index",
                Map.of("issuance_id", issuanceId, "file_key", fileKey));

        return new IndexResult(
                issuanceId,
                asInt(response.get("pages")),
                asInt(response.get("chunks")),
                asString(response.get("embedding_model")));
    }

    @Override
    public boolean available() {
        return circuitBreaker.state() != AiCircuitBreaker.State.OPEN;
    }

    public AiCircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        return circuitBreaker.execute(() -> {
            try {
                Map<String, Object> response = restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                return response == null ? Map.<String, Object>of() : response;
            } catch (RestClientException e) {
                callFailures.increment();
                // 원인 메시지에 요청 본문(질문 텍스트)이 실릴 수 있으므로 예외 타입만 남긴다
                throw new AiUnavailableException(
                        "AI 서비스 호출 실패: " + path + " (" + e.getClass().getSimpleName() + ")", e);
            }
        });
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b && b;
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static List<Integer> asIntegerList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Number.class::isInstance)
                .map(item -> ((Number) item).intValue())
                .toList();
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).toList();
    }
}

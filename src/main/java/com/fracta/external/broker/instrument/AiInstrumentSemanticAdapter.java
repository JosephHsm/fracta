package com.fracta.external.broker.instrument;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-service의 로컬 임베딩 검색을 부른다.
 *
 * <p>실패를 삼킨다 — 의미 검색이 안 되면 문자 검색 결과만 보이면 되고,
 * 종목 검색이 AI 서비스 장애로 통째로 죽어서는 안 된다.
 */
@Component
public class AiInstrumentSemanticAdapter implements InstrumentSemanticPort {

    private static final Logger log = LoggerFactory.getLogger(AiInstrumentSemanticAdapter.class);

    private final RestClient restClient;

    public AiInstrumentSemanticAdapter(@Value("${ai.service-url}") String serviceUrl,
                                       @Value("${ai.instrument-search-timeout-millis:3000}") long timeoutMillis) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMillis).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMillis).toMillis());
        this.restClient = RestClient.builder().baseUrl(serviceUrl).requestFactory(factory).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Hit> search(String query, int limit) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/ai/instruments/search")
                    .body(Map.of("query", query, "limit", limit))
                    .retrieve()
                    .body(Map.class);

            if (response == null || !(response.get("hits") instanceof List<?> hits)) {
                return List.of();
            }
            return hits.stream()
                    .filter(Map.class::isInstance)
                    .map(h -> (Map<String, Object>) h)
                    .map(h -> new Hit(
                            String.valueOf(h.get("code")),
                            String.valueOf(h.get("name")),
                            String.valueOf(h.get("kind")),
                            h.get("similarity") instanceof Number n ? n.doubleValue() : 0d))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("종목 의미 검색 실패 — 문자 검색 결과만 반환한다: {}", e.getMessage());
            return List.of();
        }
    }
}

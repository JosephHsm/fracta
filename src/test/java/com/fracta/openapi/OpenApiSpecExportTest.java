package com.fracta.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fracta.support.IntegrationTestBase;

/**
 * springdoc이 만든 OpenAPI 스펙을 파일로 내보낸다. `packages/api-client`가 이 파일에서
 * 타입 클라이언트를 생성하므로(FSD §11.1), 스펙은 손으로 고치지 않는다.
 *
 * <p>테스트로 만든 이유는 두 가지다. ① 앱을 따로 띄우지 않고 {@code ./gradlew test}만으로
 * 재생성된다. ② 컨트롤러가 바뀌면 커밋되지 않은 스펙 변경이 `git diff`에 그대로 드러난다 —
 * 프론트가 조용히 어긋나는 것을 막는 장치다.
 */
class OpenApiSpecExportTest extends IntegrationTestBase {

    /** 재생성 대상. 이 경로를 바꾸면 `packages/api-client/package.json`도 함께 고쳐야 한다. */
    private static final Path SPEC_PATH = Path.of("docs", "openapi", "fracta-openapi.json");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("/v3/api-docs 스펙을 docs/openapi/fracta-openapi.json으로 내보낸다")
    void exportsOpenApiSpec() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode spec = objectMapper.readTree(response.getBody());
        JsonNode paths = spec.path("paths");

        // 투자자 웹앱과 개발자 포털이 각각 물고 있는 대표 경로가 빠지면 생성 클라이언트도 빈다
        assertThat(paths.has("/api/v1/issuances")).as("investor-web 발행 목록").isTrue();
        assertThat(paths.has("/open/v1/tokens")).as("dev-portal 공개 API").isTrue();
        assertThat(paths.has("/api/v1/developer/clients")).as("dev-portal 앱 관리").isTrue();
        assertThat(paths.has("/api/v1/developer/logs")).as("dev-portal 호출 로그").isTrue();
        assertThat(spec.path("components").path("schemas").size()).isGreaterThan(0);

        // 키 정렬 후 저장 — springdoc의 리플렉션 순서가 실행마다 흔들려도 diff가 뜨지 않게 한다
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = objectMapper.readValue(response.getBody(), Map.class);
        stripTestOnlyPaths(tree);
        pinServerUrl(tree);

        String json = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(tree);

        Files.createDirectories(SPEC_PATH.getParent());
        Files.writeString(SPEC_PATH, json + System.lineSeparator());

        assertThat(Files.size(SPEC_PATH)).isGreaterThan(1_000L);

        // 내보낸 결과 검증 — 테스트 전용 경로가 남으면 클라이언트에 없는 엔드포인트가 생긴다
        JsonNode exported = objectMapper.readTree(Files.readString(SPEC_PATH)).path("paths");
        assertThat(exported.size()).isGreaterThan(30);
        exported.properties().stream()
                .map(Map.Entry::getKey)
                .forEach(path -> assertThat(path)
                        .as("예상 밖 경로 — 프론트 클라이언트 분류가 깨진다")
                        .matches("^/(api/v1|open)/.*"));
    }

    @Test
    @DisplayName("보안 스킴이 스펙에 실린다 — 없으면 생성 클라이언트가 Authorization 헤더를 안 붙인다")
    void declaresBearerSecurityScheme() throws Exception {
        JsonNode spec = objectMapper.readTree(
                rest.getForEntity("/v3/api-docs", String.class).getBody());

        // openapi-generator는 이 선언을 보고 헤더 주입 코드를 만든다. 선언이 없으면
        // 서버가 멀쩡해도 프론트의 모든 인증 요청이 401로 떨어진다 — 실제로 그 사고를 냈다.
        JsonNode scheme = spec.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.isMissingNode()).as("bearerAuth 스킴 선언").isFalse();
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");

        assertThat(spec.path("security").isArray()).as("전역 security 요구사항").isTrue();
        assertThat(spec.path("security").toString()).contains("bearerAuth");
    }

    /**
     * 스펙은 테스트 컨텍스트에서 뽑기 때문에 {@code com.fracta.support}의 테스트 전용 컨트롤러가
     * 함께 잡힌다. 그대로 두면 생성 클라이언트에 실제로 없는 엔드포인트가 생긴다.
     */
    /**
     * 서버 URL을 고정한다. springdoc은 실제 바인딩 포트를 넣는데, 테스트는 랜덤 포트로 뜬다
     * — 그대로 두면 스펙이 매 실행마다 바뀌어 git이 늘 더럽고, 공개 스펙에 무의미한
     * `localhost:52888` 같은 값이 박힌다. 키 정렬과 같은 취지다.
     */
    private static void pinServerUrl(Map<String, Object> spec) {
        spec.put("servers", List.of(Map.of(
                "url", "http://localhost:8080",
                "description", "로컬 기동 (docker compose up)")));
    }

    private static void stripTestOnlyPaths(Map<String, Object> spec) {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        paths.keySet().removeIf(path -> path.startsWith("/test-support/"));
    }
}

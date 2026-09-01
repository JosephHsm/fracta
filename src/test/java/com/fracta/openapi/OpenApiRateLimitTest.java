package com.fracta.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.OpenApiTestSupport;

/** Rate Limit (OA-04, OA-05). Phase 5의 슬라이딩 윈도우를 공유한다. */
class OpenApiRateLimitTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OpenApiTestSupport openApi;

    @Autowired
    AuthTestSupport auth;

    private ResponseEntity<String> get(String token) {
        return rest.exchange("/open/v1/tokens", HttpMethod.GET,
                new HttpEntity<>(openApi.bearer(token)), String.class);
    }

    @Test
    @DisplayName("성공 응답에도 X-RateLimit-* 3개 헤더가 붙는다")
    void headersOnSuccess() {
        long owner = auth.signupAndLogin("rl-owner1").id();
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE, 10, 10_000);

        ResponseEntity<String> response = get(client.accessToken());

        assertThat(response.getStatusCode()).as("응답 본문: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
        assertThat(response.getHeaders().getFirst("X-RateLimit-Reset")).isNotNull();
    }

    @Test
    @DisplayName("초당 한도 초과 → 429 + Retry-After + RATE_LIMIT_EXCEEDED")
    void perSecondLimit() throws Exception {
        long owner = auth.signupAndLogin("rl-owner2").id();
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE, 3, 10_000);

        for (int i = 0; i < 3; i++) {
            assertThat(get(client.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> limited = get(client.accessToken());
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(limited.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(objectMapper.readTree(limited.getBody()).path("error").path("code").asText())
                .isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("경계 시점 버스트 차단 — 창이 지나기 전에는 리셋되지 않는다")
    void boundaryBurstBlocked() throws Exception {
        long owner = auth.signupAndLogin("rl-owner3").id();
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE, 3, 10_000);

        for (int i = 0; i < 3; i++) {
            assertThat(get(client.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        Thread.sleep(400);   // 아직 1초 창 안이다
        assertThat(get(client.accessToken()).getStatusCode())
                .as("고정 윈도우였다면 여기서 통과했을 지점")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        Thread.sleep(800);   // 창이 지나면 다시 허용
        assertThat(get(client.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("일 한도 초과 → 429 (초당 한도와 별개로 동작)")
    void perDayLimit() throws Exception {
        long owner = auth.signupAndLogin("rl-owner4").id();
        // 초당은 넉넉히, 일 한도만 2건으로 잡는다
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE, 100, 2);

        assertThat(get(client.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(client.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> limited = get(client.accessToken());
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(objectMapper.readTree(limited.getBody()).path("error").path("code").asText())
                .isEqualTo("RATE_LIMIT_EXCEEDED");
    }
}

package com.fracta.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

class ErrorResponseFormatTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTestSupport authSupport;

    @Test
    @DisplayName("도메인 예외는 FSD §7.1 실패 포맷 { error{code,message,details}, meta } 그대로 반환된다")
    void domainExceptionReturnsCommonErrorFormat() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/test-support/boom", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode body = objectMapper.readTree(response.getBody());
        JsonNode error = body.path("error");
        assertThat(error.path("code").asText()).isEqualTo("FUND_INSUFFICIENT_UNITS");
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.path("details").path("available").asLong()).isEqualTo(1L);
        assertThat(error.path("details").path("requested").asLong()).isEqualTo(5L);

        JsonNode meta = body.path("meta");
        assertThat(meta.path("requestId").asText()).isNotBlank();
        assertThat(meta.path("timestamp").asText()).isNotBlank();
        assertThat(meta.path("requestId").asText())
                .isEqualTo(response.getHeaders().getFirst("X-Request-Id"));
    }

    @Test
    @DisplayName("필수 쿼리 파라미터 누락은 500이 아니라 400 VALID_INVALID_INPUT이다")
    void missingRequiredParameterIsBadRequest() throws Exception {
        var auth = authSupport.signupAndLogin("missing-param");

        // clientId 없이 호출한다 — 전용 핸들러가 없으면 마지막 Exception 핸들러가 잡아
        // 500 INTERNAL_ERROR가 되고, 호출자는 서버가 깨진 줄 안다.
        ResponseEntity<String> response = rest.exchange("/api/v1/developer/dashboard",
                HttpMethod.GET, new HttpEntity<>(authSupport.bearer(auth.token())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode error = objectMapper.readTree(response.getBody()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("VALID_INVALID_INPUT");
        assertThat(error.path("details").has("clientId"))
                .as("어떤 파라미터가 빠졌는지 알려줘야 한다")
                .isTrue();
    }

}

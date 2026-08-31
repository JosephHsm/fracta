package com.fracta.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.support.IntegrationTestBase;

class ErrorResponseFormatTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

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
}

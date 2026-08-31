package com.fracta.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.support.IntegrationTestBase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RequestIdIntegrationTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("응답 meta.requestId = X-Request-Id 헤더 = 로그 MDC requestId")
    void requestIdFlowsThroughResponseAndLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            ResponseEntity<String> response = rest.getForEntity("/api/v1/test/ping", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String headerRequestId = response.getHeaders().getFirst(RequestIdFilter.HEADER_NAME);
            assertThat(headerRequestId).isNotBlank();

            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(body.path("data").path("pong").asBoolean()).isTrue();
            assertThat(body.path("meta").path("requestId").asText()).isEqualTo(headerRequestId);
            assertThat(body.path("meta").path("timestamp").asText()).isNotBlank();

            // 로그에서 동일 requestId 검색 가능해야 한다
            assertThat(appender.list)
                    .anyMatch(event -> headerRequestId.equals(
                            event.getMDCPropertyMap().get(RequestIdFilter.REQUEST_ID_KEY)));
        } finally {
            root.detachAppender(appender);
        }
    }
}

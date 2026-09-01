package com.fracta.openapi;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.openapi.webhook.WebhookDelivery;
import com.fracta.openapi.webhook.WebhookDeliveryRepository;
import com.fracta.openapi.webhook.WebhookDispatcher;
import com.fracta.openapi.webhook.WebhookEndpoint;
import com.fracta.openapi.webhook.WebhookEndpointRepository;
import com.fracta.openapi.webhook.WebhookEvent;
import com.fracta.openapi.webhook.WebhookSigner;
import com.github.tomakehurst.wiremock.WireMockServer;

/** 웹훅 실제 HTTP 발송·HMAC 원문 서명·5회 재시도·DLQ 검증. */
class WebhookDeliveryTest {

    private WireMockServer receiver;
    private WebhookEndpointRepository endpoints;
    private WebhookDeliveryRepository deliveries;

    @BeforeEach
    void startReceiver() {
        receiver = new WireMockServer(options().dynamicPort());
        receiver.start();
        endpoints = mock(WebhookEndpointRepository.class);
        deliveries = mock(WebhookDeliveryRepository.class);
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop();
    }

    @Test
    @DisplayName("직렬화된 원문 그대로 HMAC 서명해 발송한다")
    void sendsRawBodyWithValidSignature() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(204)));
        String secret = "whsec_delivery_test";
        String rawBody = "{\"orderId\":42,\"status\":\"FILLED\"}";
        WebhookDelivery delivery = new WebhookDelivery(7L, WebhookEvent.ORDER_FILLED.value(), rawBody);
        WebhookEndpoint endpoint = new WebhookEndpoint("cli_test", 2L, receiver.baseUrl() + "/hook",
                secret, Set.of(WebhookEvent.ORDER_FILLED));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));
        when(endpoints.findById(7L)).thenReturn(Optional.of(endpoint));
        when(deliveries.save(delivery)).thenReturn(delivery);

        WebhookDelivery result = dispatcher(1_000).deliverWithRetry(1L);

        assertThat(result.status()).isEqualTo(WebhookDelivery.Status.DELIVERED);
        assertThat(result.attempts()).isBetween(1, 5);
        verify(deliveries).save(delivery);
        var request = receiver.getAllServeEvents().getFirst().getRequest();
        assertThat(request.getBodyAsString()).isEqualTo(rawBody);
        assertThat(WebhookSigner.verify(secret, rawBody,
                request.getHeader(WebhookSigner.HEADER), Instant.now())).isTrue();
    }

    @Test
    @DisplayName("수신 실패 → 지수 백오프 5회 재시도 후 DEAD(DLQ) 기록")
    void retriesFiveTimesThenMovesToDlq() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(500)));
        WebhookDelivery delivery = new WebhookDelivery(7L, WebhookEvent.TOKEN_LISTED.value(), "{}");
        WebhookEndpoint endpoint = new WebhookEndpoint("cli_test", 2L, receiver.baseUrl() + "/hook",
                "whsec_retry", Set.of(WebhookEvent.TOKEN_LISTED));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));
        when(endpoints.findById(7L)).thenReturn(Optional.of(endpoint));
        when(deliveries.save(delivery)).thenReturn(delivery);

        WebhookDelivery result = dispatcher(50).deliverWithRetry(1L);

        assertThat(result.status()).isEqualTo(WebhookDelivery.Status.DEAD);
        assertThat(result.attempts()).isEqualTo(5);
        assertThat(result.lastError()).isNotBlank();
        verify(deliveries).save(delivery);
        receiver.verify(5, postRequestedFor(urlEqualTo("/hook")));
    }

    private WebhookDispatcher dispatcher(long timeoutMillis) {
        // 50ms 설정은 실패 테스트에서 10ms 기반 백오프(10/20/40/80ms)를 사용한다.
        return new WebhookDispatcher(endpoints, deliveries, mock(StringRedisTemplate.class),
                new ObjectMapper(), RestClient.builder(), timeoutMillis);
    }
}

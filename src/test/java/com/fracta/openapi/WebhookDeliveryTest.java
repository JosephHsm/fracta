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

    /** WebhookDispatcher.MAX_ATTEMPTS 와 같은 값. 상수가 패키지 밖으로 열려 있지 않다. */
    private static final int MAX_ATTEMPTS = 5;

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

        WebhookDelivery result = dispatcher(1_000).attemptDelivery(1L);

        assertThat(result.status()).isEqualTo(WebhookDelivery.Status.DELIVERED);
        assertThat(result.attempts()).isEqualTo(1);
        verify(deliveries).save(delivery);
        var request = receiver.getAllServeEvents().getFirst().getRequest();
        assertThat(request.getBodyAsString()).isEqualTo(rawBody);
        assertThat(WebhookSigner.verify(secret, rawBody,
                request.getHeader(WebhookSigner.HEADER), Instant.now())).isTrue();
    }

    @Test
    @DisplayName("수신 실패 → 재시도를 그 자리에서 기다리지 않고 다음 시도 시각만 예약한다")
    void schedulesRetryInsteadOfSleeping() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(500)));
        WebhookDelivery delivery = deliveryFor(WebhookEvent.TOKEN_LISTED);
        stubRepositories(delivery);

        Instant before = Instant.now();
        WebhookDelivery result = dispatcher(50).attemptDelivery(1L);
        long elapsedMillis = java.time.Duration.between(before, Instant.now()).toMillis();

        // 아직 살아 있고, 다음 시도는 미래로 예약됐다
        assertThat(result.status()).isEqualTo(WebhookDelivery.Status.PENDING);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.nextAttemptAt()).isAfter(before);
        assertThat(result.lastError()).isNotBlank();
        // 수신 측에는 딱 한 번만 갔다 — 한 호출에서 5회를 몰아치지 않는다
        receiver.verify(1, postRequestedFor(urlEqualTo("/hook")));
        // 백오프를 워커 스레드에서 기다렸다면 이보다 훨씬 오래 걸린다
        assertThat(elapsedMillis)
                .as("재시도 대기를 워커 스레드에서 잡고 있다 (%dms)", elapsedMillis)
                .isLessThan(1_000);
    }

    @Test
    @DisplayName("5회를 소진하면 DEAD(DLQ)로 남는다")
    void movesToDlqAfterFiveAttempts() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(500)));
        WebhookDelivery delivery = deliveryFor(WebhookEvent.TOKEN_LISTED);
        stubRepositories(delivery);
        WebhookDispatcher dispatcher = dispatcher(50);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            dispatcher.attemptDelivery(1L);
        }

        assertThat(delivery.status()).isEqualTo(WebhookDelivery.Status.DEAD);
        assertThat(delivery.attempts()).isEqualTo(5);
        assertThat(delivery.lastError()).isNotBlank();
        receiver.verify(5, postRequestedFor(urlEqualTo("/hook")));
    }

    @Test
    @DisplayName("DEAD 가 된 건은 더 시도하지 않는다")
    void deadDeliveryIsNotRetried() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(500)));
        WebhookDelivery delivery = deliveryFor(WebhookEvent.TOKEN_LISTED);
        stubRepositories(delivery);
        WebhookDispatcher dispatcher = dispatcher(50);

        for (int i = 0; i < MAX_ATTEMPTS + 3; i++) {
            dispatcher.attemptDelivery(1L);
        }

        assertThat(delivery.attempts()).isEqualTo(5);
        receiver.verify(5, postRequestedFor(urlEqualTo("/hook")));
    }

    @Test
    @DisplayName("엔드포인트가 삭제됐으면 재시도 없이 곧바로 DEAD")
    void missingEndpointGoesStraightToDlq() {
        WebhookDelivery delivery = deliveryFor(WebhookEvent.TOKEN_LISTED);
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));
        when(endpoints.findById(7L)).thenReturn(Optional.empty());
        when(deliveries.save(delivery)).thenReturn(delivery);

        WebhookDelivery result = dispatcher(50).attemptDelivery(1L);

        assertThat(result.status()).isEqualTo(WebhookDelivery.Status.DEAD);
        assertThat(result.attempts()).isEqualTo(1);
    }

    private WebhookDelivery deliveryFor(WebhookEvent event) {
        return new WebhookDelivery(7L, event.value(), "{}");
    }

    private void stubRepositories(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = new WebhookEndpoint("cli_test", 2L, receiver.baseUrl() + "/hook",
                "whsec_retry", Set.of(WebhookEvent.TOKEN_LISTED));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));
        when(endpoints.findById(7L)).thenReturn(Optional.of(endpoint));
        when(deliveries.save(delivery)).thenReturn(delivery);
    }

    private WebhookDispatcher dispatcher(long timeoutMillis) {
        // 50ms 설정은 실패 테스트에서 10ms 기반 백오프(10/20/40/80ms)를 사용한다.
        return new WebhookDispatcher(endpoints, deliveries, mock(StringRedisTemplate.class),
                new ObjectMapper(), RestClient.builder(), timeoutMillis);
    }
}

package com.fracta.openapi.presentation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.response.ApiResponse;
import com.fracta.audit.api.Auditable;
import com.fracta.openapi.gateway.OpenApiContext;
import com.fracta.openapi.webhook.WebhookEndpoint;
import com.fracta.openapi.webhook.WebhookEvent;
import com.fracta.openapi.webhook.WebhookEndpointRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** 웹훅 등록 (FSD §7.2 12/12). scope 요구 없이 인증만으로 자기 클라이언트의 훅을 건다. */
@RestController
@Tag(name = "웹훅", description = "이벤트 수신 엔드포인트 등록")
public class OpenApiWebhookController {

    public record RegisterWebhookRequest(@NotBlank String url,
                                         @NotEmpty Set<String> events,
                                         @NotBlank String secret) {
    }

    private final WebhookEndpointRepository endpoints;

    public OpenApiWebhookController(WebhookEndpointRepository endpoints) {
        this.endpoints = endpoints;
    }

    @Operation(summary = "웹훅 등록",
            description = "이벤트: order.filled, order.partially_filled, order.cancelled, "
                    + "subscription.allotted, token.listed, token.suspended. "
                    + "발송 시 X-Fracta-Signature 헤더로 HMAC-SHA256 서명이 붙는다. "
                    + "예: {\"url\":\"https://example.com/hook\",\"events\":[\"order.filled\"],"
                    + "\"secret\":\"whsec_xxx\"}")
    @PostMapping({"/open/v1/webhooks", "/open/sandbox/v1/webhooks"})
    @ResponseStatus(HttpStatus.CREATED)
    @Auditable(action = "WEBHOOK_REGISTER", targetType = "WEBHOOK_ENDPOINT",
            targetId = "#result.data()['webhookId']")
    public ApiResponse<Map<String, Object>> register(
            @Valid @RequestBody RegisterWebhookRequest request) {
        Set<WebhookEvent> events = request.events().stream()
                .map(WebhookEvent::of)
                .collect(Collectors.toSet());

        WebhookEndpoint endpoint = endpoints.save(new WebhookEndpoint(
                OpenApiContext.current().clientId(), OpenApiContext.current().ownerInvestorId(),
                request.url(), request.secret(), events));

        Map<String, Object> body = new HashMap<>();
        body.put("webhookId", endpoint.id());
        body.put("url", endpoint.url());
        body.put("events", endpoint.events());
        // secret 은 돌려주지 않는다 — 등록한 쪽이 이미 갖고 있다
        return ApiResponse.of(body);
    }
}

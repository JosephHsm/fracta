package com.fracta.openapi.presentation;

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
            targetId = "#result.data().webhookId()")
    public ApiResponse<RegisterWebhookResponse> register(
            @Valid @RequestBody RegisterWebhookRequest request) {
        Set<WebhookEvent> events = request.events().stream()
                .map(WebhookEvent::of)
                .collect(Collectors.toSet());

        WebhookEndpoint endpoint = endpoints.save(new WebhookEndpoint(
                OpenApiContext.current().clientId(), OpenApiContext.current().ownerInvestorId(),
                request.url(), request.secret(), events));

        // secret 은 돌려주지 않는다 — 등록한 쪽이 이미 갖고 있다
        return ApiResponse.of(new RegisterWebhookResponse(endpoint.id(), endpoint.url(),
                endpoint.events()));
    }

    /**
     * 웹훅 등록 결과. secret은 의도적으로 빠져 있다.
     *
     * <p>{@code events}는 저장 형식 그대로인 쉼표 구분 문자열이다. 배열로 바꾸면 기존 파트너
     * 클라이언트가 깨지므로 JSON을 그대로 둔다.
     *
     * <p>{@code @Auditable}의 SpEL은 접근자 호출로 적는다. 기존 Map 인덱싱 문법
     * ({@code #result.data()['webhookId']})도 SpEL 인덱서가 record 컴포넌트를 읽어줘서 그대로
     * 동작하지만, 반환 타입이 Map이 아닌데 Map 문법을 남겨두면 읽는 사람이 헷갈린다.
     * 감사 로그 targetId는 통합 테스트가 검증한다.
     */
    public record RegisterWebhookResponse(Long webhookId, String url, String events) {
    }
}

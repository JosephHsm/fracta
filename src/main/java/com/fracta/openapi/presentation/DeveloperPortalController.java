package com.fracta.openapi.presentation;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.response.ApiResponse;
import com.fracta.openapi.ApiEnv;
import com.fracta.openapi.application.DeveloperPortalService;
import com.fracta.openapi.application.DeveloperPortalService.CallLogView;
import com.fracta.openapi.application.DeveloperPortalService.ClientView;
import com.fracta.openapi.application.DeveloperPortalService.DashboardView;
import com.fracta.openapi.application.DeveloperPortalService.IssuedClientView;
import com.fracta.openapi.application.DeveloperPortalService.IssuedWebhookView;
import com.fracta.openapi.application.DeveloperPortalService.WebhookDeliveryView;
import com.fracta.openapi.application.DeveloperPortalService.WebhookView;
import com.fracta.openapi.auth.ApiScope;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** 웹앱 JWT로 접근하는 개발자 포털 전용 API. 오픈 API 토큰과 인증 경계를 섞지 않는다. */
@RestController
@RequestMapping("/api/v1/developer")
@Tag(name = "개발자 포털", description = "앱, 호출 통계, 로그, 웹훅 관리")
public class DeveloperPortalController {

    public record DeveloperCreateClientRequest(
            @NotBlank @Size(max = 100) String name,
            @NotEmpty Set<ApiScope> scopes,
            ApiEnv env) {
    }

    public record DeveloperUpdateScopesRequest(@NotEmpty Set<ApiScope> scopes) {
    }

    public record DeveloperCreateWebhookRequest(
            @NotBlank String clientId,
            @NotBlank @Size(max = 500) String url,
            @NotEmpty Set<String> events) {
    }

    private final DeveloperPortalService portal;

    public DeveloperPortalController(DeveloperPortalService portal) {
        this.portal = portal;
    }

    @Operation(summary = "내 앱 목록", description = "평문 client_secret은 포함하지 않는다.")
    @GetMapping("/clients")
    public ApiResponse<List<ClientView>> clients(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(portal.clients(owner(jwt)));
    }

    @Operation(summary = "앱 등록", description = "client_secret은 이 응답에서 한 번만 표시된다.")
    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssuedClientView> registerClient(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DeveloperCreateClientRequest request) {
        ApiEnv env = request.env() == null ? ApiEnv.SANDBOX : request.env();
        return ApiResponse.of(portal.registerClient(request.name(), owner(jwt), request.scopes(), env));
    }

    @Operation(summary = "앱 시크릿 재발급", description = "기존 시크릿은 즉시 무효가 되며 새 값은 한 번만 표시된다.")
    @PostMapping("/clients/{clientId}/secret")
    public ApiResponse<IssuedClientView> rotateSecret(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String clientId) {
        return ApiResponse.of(portal.rotateSecret(clientId, owner(jwt)));
    }

    @Operation(summary = "앱 Scope 변경", description = "이후 발급되는 액세스 토큰부터 새 Scope가 적용된다.")
    @PutMapping("/clients/{clientId}/scopes")
    public ApiResponse<ClientView> updateScopes(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String clientId,
            @Valid @RequestBody DeveloperUpdateScopesRequest request) {
        return ApiResponse.of(portal.updateScopes(clientId, owner(jwt), request.scopes()));
    }

    @Operation(summary = "호출 대시보드", description = "최근 7일 호출량, 에러율, 지연시간과 일일 쿼터를 조회한다.")
    @GetMapping("/dashboard")
    public ApiResponse<DashboardView> dashboard(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String clientId) {
        return ApiResponse.of(portal.dashboard(clientId, owner(jwt)));
    }

    @Operation(summary = "호출 로그 검색", description = "엔드포인트, 상태 코드, 기간으로 최근 200건을 검색한다.")
    @GetMapping("/logs")
    public ApiResponse<List<CallLogView>> logs(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String clientId,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.of(portal.searchLogs(clientId, owner(jwt), endpoint, statusCode, from, to));
    }

    @Operation(summary = "웹훅 목록", description = "서명 시크릿은 포함하지 않는다.")
    @GetMapping("/webhooks")
    public ApiResponse<List<WebhookView>> webhooks(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String clientId) {
        return ApiResponse.of(portal.webhooks(clientId, owner(jwt)));
    }

    @Operation(operationId = "registerDeveloperWebhook", summary = "웹훅 등록",
            description = "webhook_secret은 이 응답에서 한 번만 표시된다.")
    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssuedWebhookView> registerWebhook(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DeveloperCreateWebhookRequest request) {
        return ApiResponse.of(portal.registerWebhook(request.clientId(), owner(jwt), request.url(),
                request.events()));
    }

    @Operation(summary = "웹훅 발송 이력", description = "본문과 시크릿을 제외한 최근 200건을 조회한다.")
    @GetMapping("/webhooks/{webhookId}/deliveries")
    public ApiResponse<List<WebhookDeliveryView>> deliveries(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long webhookId) {
        return ApiResponse.of(portal.deliveries(webhookId, owner(jwt)));
    }

    @Operation(summary = "웹훅 수동 재발송", description = "최종 실패(DEAD)한 발송 건만 다시 큐에 넣는다.")
    @PostMapping("/webhooks/deliveries/{deliveryId}/redeliver")
    public ApiResponse<WebhookDeliveryView> redeliver(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long deliveryId) {
        return ApiResponse.of(portal.redeliver(deliveryId, owner(jwt)));
    }

    private static long owner(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}

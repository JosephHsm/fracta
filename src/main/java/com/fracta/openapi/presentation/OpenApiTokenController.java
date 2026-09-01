package com.fracta.openapi.presentation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.response.ApiResponse;
import com.fracta.openapi.auth.ApiClientService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 토큰 발급 (FSD §7.2 1/12). 아직 토큰이 없으므로 게이트웨이 필터를 거치지 않는다.
 * LIVE·SANDBOX 경로 모두에서 발급받을 수 있고, 발급된 토큰은 자기 환경에서만 통한다.
 */
@RestController
@Tag(name = "인증", description = "OAuth2 Client Credentials")
public class OpenApiTokenController {

    private final ApiClientService clientService;

    public OpenApiTokenController(ApiClientService clientService) {
        this.clientService = clientService;
    }

    @Operation(summary = "액세스 토큰 발급",
            description = "client_credentials 그랜트. 유효기간 1시간. "
                    + "예: grant_type=client_credentials&client_id=cli_xxx&client_secret=sec_xxx")
    @PostMapping(path = {"/open/v1/oauth/token", "/open/sandbox/v1/oauth/token"})
    public ApiResponse<ApiClientService.TokenResponse> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret) {
        if (!"client_credentials".equals(grantType)) {
            throw new IllegalArgumentException("지원하지 않는 grant_type 이다: " + grantType);
        }
        return ApiResponse.of(clientService.issueToken(clientId, clientSecret));
    }
}

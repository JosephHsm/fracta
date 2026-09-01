package com.fracta.support;

import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fracta.openapi.ApiEnv;
import com.fracta.openapi.auth.ApiClientService;
import com.fracta.openapi.auth.ApiScope;

/** 오픈 API 테스트 픽스처 — 클라이언트 등록과 토큰 발급을 한 번에. */
@Component
public class OpenApiTestSupport {

    public record Client(String clientId, String clientSecret, String accessToken, long ownerId) {
    }

    private final ApiClientService clientService;

    public OpenApiTestSupport(ApiClientService clientService) {
        this.clientService = clientService;
    }

    public Client register(long ownerInvestorId, Set<ApiScope> scopes, ApiEnv env) {
        return register(ownerInvestorId, scopes, env, 10, 10_000);
    }

    public Client register(long ownerInvestorId, Set<ApiScope> scopes, ApiEnv env,
                           int perSec, int perDay) {
        var registered = clientService.register("테스트 클라이언트", ownerInvestorId, scopes,
                env, perSec, perDay);
        var token = clientService.issueToken(registered.clientId(), registered.clientSecret());
        return new Client(registered.clientId(), registered.clientSecret(),
                token.accessToken(), ownerInvestorId);
    }

    public HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public HttpHeaders bearerWithIdempotency(String accessToken, String key) {
        HttpHeaders headers = bearer(accessToken);
        headers.set("Idempotency-Key", key);
        return headers;
    }
}

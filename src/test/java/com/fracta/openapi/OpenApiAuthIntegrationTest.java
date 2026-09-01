package com.fracta.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.openapi.auth.ApiClientService;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.OpenApiTestSupport;

/** 인증·scope·환경 격리 (OA-01 ~ OA-03). */
class OpenApiAuthIntegrationTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OpenApiTestSupport openApi;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    ApiClientService clientService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("openApiJwtEncoder")
    JwtEncoder openApiJwtEncoder;

    private String errorCode(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("error").path("code").asText();
    }

    @Test
    @DisplayName("client_secret 은 DB에 평문으로 저장되지 않는다 (SHA-256 해시만)")
    void secretIsHashed() {
        long owner = auth.signupAndLogin("api-owner").id();
        var registered = clientService.register("훅", owner, Set.of(ApiScope.MARKET_READ),
                ApiEnv.LIVE, 10, 10_000);

        String stored = jdbc.queryForObject(
                "SELECT client_secret_hash FROM api_client WHERE client_id = ?",
                String.class, registered.clientId());

        assertThat(stored).isNotEqualTo(registered.clientSecret());
        assertThat(stored).hasSize(64).matches("[0-9a-f]{64}");

        Long plaintextRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_client WHERE client_secret_hash = ?",
                Long.class, registered.clientSecret());
        assertThat(plaintextRows).isZero();
    }

    @Test
    @DisplayName("client_credentials 토큰 발급 → 잘못된 시크릿은 401")
    void tokenIssuance() throws Exception {
        long owner = auth.signupAndLogin("api-owner2").id();
        var registered = clientService.register("훅", owner, Set.of(ApiScope.MARKET_READ),
                ApiEnv.LIVE, 10, 10_000);

        ResponseEntity<String> ok = requestToken(registered.clientId(), registered.clientSecret());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(ok.getBody()).path("data");
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.path("expiresIn").asLong()).isEqualTo(3_600);

        ResponseEntity<String> bad = requestToken(registered.clientId(), "sec_wrong");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(bad)).isEqualTo("AUTH_INVALID_CLIENT");
    }

    @Test
    @DisplayName("시크릿 재발급 → 기존 시크릿 즉시 무효")
    void rotateSecretInvalidatesOld() throws Exception {
        long owner = auth.signupAndLogin("api-owner3").id();
        var first = clientService.register("훅", owner, Set.of(ApiScope.MARKET_READ),
                ApiEnv.LIVE, 10, 10_000);
        assertThat(requestToken(first.clientId(), first.clientSecret()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        var rotated = clientService.rotateSecret(first.clientId());

        assertThat(rotated.clientSecret()).isNotEqualTo(first.clientSecret());
        assertThat(requestToken(first.clientId(), first.clientSecret()).getStatusCode())
                .as("기존 시크릿은 즉시 막혀야 한다").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(requestToken(first.clientId(), rotated.clientSecret()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("scope 없는 토큰으로 호출 → 403 AUTH_SCOPE_DENIED")
    void scopeDenied() throws Exception {
        long owner = auth.signupAndLogin("api-owner4").id();
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE);

        // market:read 만 가진 토큰으로 account:read 엔드포인트 호출
        ResponseEntity<String> response = get("/open/v1/accounts/balance", client.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(response)).isEqualTo("AUTH_SCOPE_DENIED");
    }

    @Test
    @DisplayName("scope 허용/거부 매트릭스 — 4개 scope 각각")
    void scopeMatrix() throws Exception {
        long owner = auth.signupAndLogin("api-owner5").id();

        var marketOnly = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE);
        assertThat(get("/open/v1/tokens", marketOnly.accessToken()).getStatusCode())
                .as("market:read → 종목 조회 허용").isEqualTo(HttpStatus.OK);
        assertThat(get("/open/v1/accounts/orders", marketOnly.accessToken()).getStatusCode())
                .as("market:read → 계좌 조회 거부").isEqualTo(HttpStatus.FORBIDDEN);

        var accountOnly = openApi.register(owner, Set.of(ApiScope.ACCOUNT_READ), ApiEnv.LIVE);
        assertThat(get("/open/v1/accounts/orders", accountOnly.accessToken()).getStatusCode())
                .as("account:read → 계좌 조회 허용").isEqualTo(HttpStatus.OK);
        assertThat(get("/open/v1/tokens", accountOnly.accessToken()).getStatusCode())
                .as("account:read → 종목 조회 거부").isEqualTo(HttpStatus.FORBIDDEN);

        // order:write / subscription:write 는 쓰기 엔드포인트에서만 통한다
        var orderOnly = openApi.register(owner, Set.of(ApiScope.ORDER_WRITE), ApiEnv.LIVE);
        assertThat(get("/open/v1/tokens", orderOnly.accessToken()).getStatusCode())
                .as("order:write → 시세 조회 거부").isEqualTo(HttpStatus.FORBIDDEN);

        var subOnly = openApi.register(owner, Set.of(ApiScope.SUBSCRIPTION_WRITE), ApiEnv.LIVE);
        assertThat(get("/open/v1/accounts/balance", subOnly.accessToken()).getStatusCode())
                .as("subscription:write → 계좌 조회 거부").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("토큰 없음·위조·웹앱 토큰 → 401 AUTH_INVALID_TOKEN")
    void invalidTokens() throws Exception {
        ResponseEntity<String> noToken = rest.exchange("/open/v1/tokens", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(noToken)).isEqualTo("AUTH_INVALID_TOKEN");

        assertThat(get("/open/v1/tokens", "not-a-jwt").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // 웹앱 JWT 로는 오픈 API 를 부를 수 없다 — 발급자가 다르다
        var webUser = auth.signupAndLogin("web-user");
        ResponseEntity<String> webToken = get("/open/v1/tokens", webUser.token());
        assertThat(webToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(webToken)).isEqualTo("AUTH_INVALID_TOKEN");
    }

    @Test
    @DisplayName("만료된 access_token → 401 AUTH_INVALID_TOKEN")
    void expiredTokenRejected() throws Exception {
        long owner = auth.signupAndLogin("expired-token-owner").id();
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE);
        java.time.Instant now = java.time.Instant.now();
        String expired = openApiJwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(com.fracta.openapi.auth.OpenApiJwtConfig.ISSUER)
                        .subject(client.clientId())
                        .issuedAt(now.minusSeconds(7_200))
                        .expiresAt(now.minusSeconds(3_600))
                        .claim("scope", ApiScope.MARKET_READ.value())
                        .claim("env", ApiEnv.LIVE.name())
                        .build())).getTokenValue();

        ResponseEntity<String> response = get("/open/v1/tokens", expired);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(response)).isEqualTo("AUTH_INVALID_TOKEN");
    }

    @Test
    @DisplayName("LIVE 클라이언트가 샌드박스 경로 호출 → 403, 반대도 403")
    void envMismatch() throws Exception {
        long owner = auth.signupAndLogin("api-owner6").id();
        var live = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE);
        var sandbox = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.SANDBOX);

        ResponseEntity<String> liveIntoSandbox = get("/open/sandbox/v1/tokens", live.accessToken());
        assertThat(liveIntoSandbox.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(liveIntoSandbox)).isEqualTo("AUTH_ENV_MISMATCH");

        ResponseEntity<String> sandboxIntoLive = get("/open/v1/tokens", sandbox.accessToken());
        assertThat(sandboxIntoLive.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(sandboxIntoLive)).isEqualTo("AUTH_ENV_MISMATCH");

        // 각자 자기 환경은 정상
        assertThat(get("/open/v1/tokens", live.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/open/sandbox/v1/tokens", sandbox.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(String path, String accessToken) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(openApi.bearer(accessToken)), String.class);
    }

    private ResponseEntity<String> requestToken(String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return rest.exchange("/open/v1/oauth/token", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }
}

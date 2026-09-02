package com.fracta.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
import org.springframework.util.LinkedMultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.sandbox.SandboxAccountProvisioner;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.OpenApiTestSupport;
import com.fracta.support.SubscriptionTestSupport;
import com.fracta.support.TradingTestSupport;
import com.fracta.issuance.domain.Issuance;

/** 샌드박스 스키마 격리·비동기 호출 로그·OpenAPI 산출물·조회 성능 완료조건. */
class OpenApiOperationalIntegrationTest extends IntegrationTestBase {

    private static final Set<String> LIVE_PATHS = Set.of(
            "/open/v1/oauth/token",
            "/open/v1/tokens",
            "/open/v1/tokens/{symbol}",
            "/open/v1/tokens/{symbol}/orderbook",
            "/open/v1/tokens/{symbol}/executions",
            "/open/v1/tokens/{symbol}/premium",
            "/open/v1/accounts/balance",
            "/open/v1/accounts/orders",
            "/open/v1/orders",
            "/open/v1/orders/{orderId}",
            "/open/v1/subscriptions",
            "/open/v1/webhooks");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    OpenApiTestSupport openApi;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TradingTestSupport trading;

    @Autowired
    SubscriptionTestSupport subscriptions;

    @Test
    @DisplayName("FSD §7.2의 12개 엔드포인트가 실제 HTTP 요청을 처리한다")
    void allTwelveEndpointsWork() throws Exception {
        var market = trading.listedMarket("MOCK-100000", 100);
        long owner = trading.investor(10_000_000);
        var client = openApi.register(owner, Set.of(ApiScope.values()), ApiEnv.LIVE, 1_000, 10_000);

        var tokenForm = new LinkedMultiValueMap<String, String>();
        tokenForm.add("grant_type", "client_credentials");
        tokenForm.add("client_id", client.clientId());
        tokenForm.add("client_secret", client.clientSecret());
        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        assertOk(rest.exchange("/open/v1/oauth/token", HttpMethod.POST,
                new HttpEntity<>(tokenForm, tokenHeaders), String.class));

        assertOk(get("/open/v1/tokens", client.accessToken()));
        assertOk(get("/open/v1/tokens/" + market.tokenSymbol(), client.accessToken()));
        assertOk(get("/open/v1/tokens/" + market.tokenSymbol() + "/orderbook", client.accessToken()));
        assertOk(get("/open/v1/tokens/" + market.tokenSymbol() + "/executions", client.accessToken()));
        assertOk(get("/open/v1/tokens/" + market.tokenSymbol() + "/premium", client.accessToken()));
        assertOk(get("/open/v1/accounts/balance", client.accessToken()));
        assertOk(get("/open/v1/accounts/orders", client.accessToken()));

        String orderKey = UUID.randomUUID().toString();
        String orderBody = """
                {"tokenSymbol":"%s","side":"BUY","orderType":"LIMIT","price":100,"units":1}
                """.formatted(market.tokenSymbol()).trim();
        ResponseEntity<String> placed = rest.exchange("/open/v1/orders", HttpMethod.POST,
                new HttpEntity<>(orderBody,
                        openApi.bearerWithIdempotency(client.accessToken(), orderKey)), String.class);
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = objectMapper.readTree(placed.getBody()).path("data").path("orderId").asText();

        ResponseEntity<String> cancelled = rest.exchange("/open/v1/orders/" + orderId,
                HttpMethod.DELETE, new HttpEntity<>(openApi.bearerWithIdempotency(
                        client.accessToken(), UUID.randomUUID().toString())), String.class);
        assertOk(cancelled);

        var subscribing = subscriptions.subscribingIssuance(
                Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        String subscriptionBody = "{\"issuanceId\":%d,\"units\":1}"
                .formatted(subscribing.issuanceId());
        ResponseEntity<String> subscribed = rest.exchange("/open/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionBody, openApi.bearerWithIdempotency(
                        client.accessToken(), UUID.randomUUID().toString())), String.class);
        assertThat(subscribed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String webhookBody = """
                {"url":"https://example.com/fracta-hook","events":["order.filled"],
                 "secret":"whsec_smoke_test"}
                """;
        ResponseEntity<String> webhook = rest.exchange("/open/v1/webhooks", HttpMethod.POST,
                new HttpEntity<>(webhookBody, openApi.bearer(client.accessToken())), String.class);
        assertThat(webhook.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 응답 계약: secret은 절대 돌려주지 않는다
        JsonNode webhookData = objectMapper.readTree(webhook.getBody()).path("data");
        long webhookId = webhookData.path("webhookId").asLong();
        assertThat(webhookId).isPositive();
        assertThat(webhookData.has("secret")).isFalse();

        // 감사 로그의 targetId가 채워져야 한다. @Auditable SpEL이 응답 형태에 의존하므로
        // 응답을 Map에서 record로 바꿀 때 여기가 조용히 비는 사고가 나기 쉽다.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                 WHERE action = 'WEBHOOK_REGISTER' AND target_id = ?
                """, Long.class, String.valueOf(webhookId)))
                .as("WEBHOOK_REGISTER 감사 로그에 webhookId가 기록되어야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("샌드박스 가상 계정과 LIVE 계정은 별도 스키마·별도 잔고를 사용한다")
    void sandboxDataIsCompletelyIsolated() throws Exception {
        long owner = auth.signupAndLogin("sandbox-isolation-owner").id();
        var live = openApi.register(owner, Set.of(ApiScope.ACCOUNT_READ), ApiEnv.LIVE);
        var sandbox = openApi.register(owner, Set.of(ApiScope.ACCOUNT_READ), ApiEnv.SANDBOX);

        ResponseEntity<String> liveBalance = get("/open/v1/accounts/balance", live.accessToken());
        ResponseEntity<String> sandboxBalance = get(
                "/open/sandbox/v1/accounts/balance", sandbox.accessToken());

        assertThat(liveBalance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sandboxBalance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(liveBalance.getBody()).path("data").path("cashBalance").asLong())
                .isZero();
        assertThat(objectMapper.readTree(sandboxBalance.getBody()).path("data").path("cashBalance").asLong())
                .isEqualTo(SandboxAccountProvisioner.INITIAL_CASH);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.investor WHERE id = ?",
                Long.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sandbox.investor WHERE id = ?",
                Long.class, owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("인증된 성공·실패 호출이 응답 후 비동기로 api_call_log에 기록된다")
    void everyAuthenticatedCallIsLoggedAsynchronously() throws Exception {
        long owner = auth.signupAndLogin("api-log-owner").id();
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE, 100, 10_000);

        assertThat(get("/open/v1/tokens", client.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        // scope 없는 account 호출도 client 식별 이후 실패하므로 로그 대상이다.
        assertThat(get("/open/v1/accounts/orders", client.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        awaitLogCount(client.clientId(), 2);
        var rows = jdbc.queryForList("""
                SELECT endpoint, status_code, latency_ms, idempotency_key, body_hash
                  FROM api_call_log WHERE client_id = ? ORDER BY id
                """, client.clientId());
        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(((Number) row.get("latency_ms")).longValue()).isGreaterThanOrEqualTo(0);
            assertThat(row.get("body_hash")).as("본문 대신 해시만 저장; GET은 본문 없음").isNull();
        });
        assertThat(rows.stream().map(r -> ((Number) r.get("status_code")).intValue()))
                .contains(HttpStatus.OK.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("OpenAPI JSON에 FSD §7.2의 12개 LIVE 엔드포인트와 설명이 모두 존재한다")
    void openApiSpecContainsAllTwelveEndpoints() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode paths = objectMapper.readTree(response.getBody()).path("paths");

        assertThat(LIVE_PATHS).allSatisfy(path -> {
            JsonNode operations = paths.path(path);
            assertThat(operations.isMissingNode()).as("누락 경로: %s", path).isFalse();
            JsonNode operation = operations.elements().next();
            assertThat(operation.path("summary").asText()).as("summary: %s", path).isNotBlank();
            assertThat(operation.path("description").asText()).as("description: %s", path).isNotBlank();
        });
    }

    @Test
    @DisplayName("조회 API p95 < 200ms")
    void readApiP95UnderTwoHundredMillis() {
        long owner = auth.signupAndLogin("api-p95-owner").id();
        var client = openApi.register(owner, Set.of(ApiScope.MARKET_READ), ApiEnv.LIVE, 1_000, 10_000);
        for (int i = 0; i < 5; i++) {
            get("/open/v1/tokens", client.accessToken());
        }

        java.util.List<Long> timings = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long started = System.nanoTime();
            ResponseEntity<String> response = get("/open/v1/tokens", client.accessToken());
            timings.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        timings.sort(Long::compareTo);
        long p95 = timings.get((int) Math.ceil(timings.size() * 0.95) - 1);
        assertThat(p95).as("timings(ms)=%s", timings).isLessThan(200);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(openApi.bearer(token)), String.class);
    }

    private void assertOk(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void awaitLogCount(String clientId, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM api_call_log WHERE client_id = ?", Long.class, clientId);
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("비동기 호출 로그가 5초 안에 기록되지 않았다: " + clientId);
    }
}

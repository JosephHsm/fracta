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
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.openapi.auth.ApiClientRepository;
import com.fracta.openapi.log.ApiCallLog;
import com.fracta.openapi.log.ApiCallLogRepository;
import com.fracta.openapi.webhook.WebhookDelivery;
import com.fracta.openapi.webhook.WebhookDeliveryRepository;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

/** Phase 10 개발자 포털의 소유권·시크릿 1회 노출·통계·웹훅 계약. */
@TestPropertySource(properties = "openapi.webhook.timeout-millis=50")
class DeveloperPortalIntegrationTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    ApiClientRepository clients;

    @Autowired
    ApiCallLogRepository callLogs;

    @Autowired
    WebhookDeliveryRepository deliveries;

    @Test
    @DisplayName("앱 시크릿은 발급 응답에만 한 번 보이고 다른 투자자는 앱을 조작할 수 없다")
    void clientSecretIsShownOnceAndOwnershipIsEnforced() throws Exception {
        var owner = auth.signupAndLogin("portal-owner");
        var stranger = auth.signupAndLogin("portal-stranger");

        ResponseEntity<String> created = post("/api/v1/developer/clients", """
                {"name":"결제 샌드박스","scopes":["MARKET_READ","ACCOUNT_READ"],"env":"SANDBOX"}
                """, owner.token());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode issued = data(created);
        String clientId = issued.path("clientId").asText();
        String firstSecret = issued.path("clientSecret").asText();
        assertThat(clientId).startsWith("cli_");
        assertThat(firstSecret).startsWith("sec_");
        assertThat(clients.findByClientId(clientId).orElseThrow().clientSecretHash())
                .doesNotContain(firstSecret);

        ResponseEntity<String> listed = get("/api/v1/developer/clients", owner.token());
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).contains(clientId).doesNotContain("clientSecret");

        ResponseEntity<String> updated = put("/api/v1/developer/clients/" + clientId + "/scopes",
                "{\"scopes\":[\"MARKET_READ\",\"ORDER_WRITE\"]}", owner.token());
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(updated).path("scopes").toString())
                .contains("MARKET_READ", "ORDER_WRITE");

        ResponseEntity<String> rotated = post(
                "/api/v1/developer/clients/" + clientId + "/secret", null, owner.token());
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(rotated).path("clientSecret").asText()).startsWith("sec_")
                .isNotEqualTo(firstSecret);

        ResponseEntity<String> forbidden = post(
                "/api/v1/developer/clients/" + clientId + "/secret", null, stranger.token());
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(objectMapper.readTree(forbidden.getBody()).path("error").path("code").asText())
                .isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    @DisplayName("대시보드·로그 검색과 웹훅 이력/재발송은 소유 앱 데이터만 반환한다")
    void dashboardLogsAndWebhookHistoryWork() throws Exception {
        var owner = auth.signupAndLogin("portal-operations");
        JsonNode client = data(post("/api/v1/developer/clients", """
                {"name":"운영 콘솔","scopes":["MARKET_READ"],"env":"SANDBOX"}
                """, owner.token()));
        String clientId = client.path("clientId").asText();

        callLogs.saveAllAndFlush(Set.of(
                new ApiCallLog(clientId, "/open/sandbox/v1/tokens", "GET", 200, 12, null, null),
                new ApiCallLog(clientId, "/open/sandbox/v1/accounts/balance", "GET", 403, 8, null, null)));

        ResponseEntity<String> dashboard = get(
                "/api/v1/developer/dashboard?clientId=" + clientId, owner.token());
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(dashboard).path("callsToday").asLong()).isEqualTo(2);
        assertThat(data(dashboard).path("errorRatePercent").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(data(dashboard).path("dailyUsage")).hasSize(7);

        ResponseEntity<String> logs = get(
                "/api/v1/developer/logs?clientId=" + clientId + "&statusCode=403", owner.token());
        assertThat(logs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(logs)).hasSize(1);
        assertThat(data(logs).get(0).path("endpoint").asText()).contains("balance");

        ResponseEntity<String> webhookCreated = post("/api/v1/developer/webhooks", """
                {"clientId":"%s","url":"http://127.0.0.1:1/fracta-hook",
                 "events":["order.filled","token.listed"]}
                """.formatted(clientId), owner.token());
        assertThat(webhookCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long webhookId = data(webhookCreated).path("webhookId").asLong();
        assertThat(data(webhookCreated).path("webhookSecret").asText()).startsWith("whsec_");

        ResponseEntity<String> webhookList = get(
                "/api/v1/developer/webhooks?clientId=" + clientId, owner.token());
        assertThat(webhookList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhookList.getBody()).doesNotContain("webhookSecret");

        WebhookDelivery dead = new WebhookDelivery(webhookId, "order.filled", "{\"orderId\":1}");
        dead.markDead();
        dead = deliveries.saveAndFlush(dead);

        ResponseEntity<String> history = get(
                "/api/v1/developer/webhooks/" + webhookId + "/deliveries", owner.token());
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(history)).hasSize(1);
        assertThat(data(history).get(0).path("status").asText()).isEqualTo("DEAD");

        ResponseEntity<String> retried = post(
                "/api/v1/developer/webhooks/deliveries/" + dead.id() + "/redeliver",
                null, owner.token());
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(retried).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Swagger UI는 동일 출처 개발자 포털 iframe에서만 표시할 수 있다")
    void swaggerUiAllowsSameOriginFrame() {
        ResponseEntity<String> response = rest.getForEntity("/swagger-ui.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("SAMEORIGIN");
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(auth.bearer(token)), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        return rest.exchange(path, HttpMethod.POST, entity(body, token), String.class);
    }

    private ResponseEntity<String> put(String path, String body, String token) {
        return rest.exchange(path, HttpMethod.PUT, entity(body, token), String.class);
    }

    private HttpEntity<String> entity(String body, String token) {
        HttpHeaders headers = auth.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("data");
    }
}

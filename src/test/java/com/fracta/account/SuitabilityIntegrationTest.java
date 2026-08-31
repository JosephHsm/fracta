package com.fracta.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.account.api.InvestorId;
import com.fracta.account.application.RiskProfileService;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

class SuitabilityIntegrationTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    RiskProfileService riskProfileService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("성향 2등급 + 상품 4등급 → 403 SUIT_PROFILE_MISMATCH, 확인 서명 후 통과·기록·감사 로그")
    void mismatchBlockedThenAllowedByAck() throws Exception {
        var user = auth.signupAndLogin("suit-user");
        // 합 16 → 2등급 (안정추구형)
        riskProfileService.submit(InvestorId.of(user.id()), List.of(2, 2, 2, 2, 2, 2, 2, 2));

        // 차단: 403 + SUIT_PROFILE_MISMATCH
        ResponseEntity<String> blocked = get("/api/v1/investors/me/suitability?productGrade=4", user.token());
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode error = objectMapper.readTree(blocked.getBody()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("SUIT_PROFILE_MISMATCH");
        assertThat(error.path("details").path("productGrade").asInt()).isEqualTo(4);
        assertThat(error.path("details").path("investorGrade").asInt()).isEqualTo(2);

        // 확인 서명 제출
        ResponseEntity<String> ack = rest.exchange("/api/v1/investors/me/suitability-ack", HttpMethod.POST,
                new HttpEntity<>(Map.of("productGrade", 4), auth.bearer(user.token())), String.class);
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);

        // suitability_ack 기록 확인
        Long ackCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suitability_ack WHERE investor_id = ? AND product_grade = 4",
                Long.class, user.id());
        assertThat(ackCount).isEqualTo(1);

        // 감사 로그: 행위자 = 투자자 id (JWT sub), action = SUITABILITY_ACK
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'SUITABILITY_ACK' AND actor = ?",
                Long.class, String.valueOf(user.id()));
        assertThat(auditCount).isEqualTo(1);

        // 동일 요청 재시도 → 통과 (ALLOWED_BY_ACK)
        ResponseEntity<String> allowed = get("/api/v1/investors/me/suitability?productGrade=4", user.token());
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(allowed.getBody()).path("data").path("decision").asText())
                .isEqualTo("ALLOWED_BY_ACK");

        // 서명(4등급)은 5등급 상품을 커버하지 않는다
        ResponseEntity<String> stillBlocked = get("/api/v1/investors/me/suitability?productGrade=5", user.token());
        assertThat(stillBlocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("성향 진단 미실시 → 403 SUIT_PROFILE_REQUIRED")
    void noProfileBlocked() throws Exception {
        var user = auth.signupAndLogin("no-profile");
        ResponseEntity<String> blocked = get("/api/v1/investors/me/suitability?productGrade=1", user.token());
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(objectMapper.readTree(blocked.getBody()).path("error").path("code").asText())
                .isEqualTo("SUIT_PROFILE_REQUIRED");
    }

    @Test
    @DisplayName("성향등급 ≥ 상품등급이면 그대로 허용")
    void allowedWhenGradeSufficient() throws Exception {
        var user = auth.signupAndLogin("ok-user");
        riskProfileService.submit(InvestorId.of(user.id()), List.of(5, 5, 5, 5, 5, 5, 5, 5)); // 40 → 5등급

        ResponseEntity<String> allowed = get("/api/v1/investors/me/suitability?productGrade=5", user.token());
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(allowed.getBody()).path("data").path("decision").asText())
                .isEqualTo("ALLOWED");
    }

    private ResponseEntity<String> get(String url, String token) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth.bearer(token)), String.class);
    }
}

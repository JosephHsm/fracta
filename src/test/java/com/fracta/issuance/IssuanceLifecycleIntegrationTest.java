package com.fracta.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.common.money.Units;
import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

/** 발행 → 상장까지 상태 전이 통합 테스트 (FSD §14 명시 조건). */
class IssuanceLifecycleIntegrationTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    AssetService assetService;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    LedgerPort ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("DRAFT→…→LISTED 전 과정 + 청약 개시 스케줄러 + 상장 시 원장 전량 발행 + 승인 감사 로그")
    void fullLifecycleToListed() throws Exception {
        var issuer = auth.signupAndLogin("issuer");
        String adminToken = auth.adminToken();

        UnderlyingAsset asset = assetService.create(issuer.id(), "테스트 리츠",
                UnderlyingAsset.AssetType.REIT, "LIFE", null, 100, "라이프사이클 테스트");
        // 청약 시작 시각이 이미 지난 발행 건 — IS-06 스케줄러가 SUBSCRIBING으로 올린다
        var created = issuanceService.create(asset.id(), 1_000, 500,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3_600));
        long id = created.issuanceId();
        String symbol = created.tokenSymbol();

        issuanceService.submit(id);
        assertThat(issuanceService.get(id).status()).isEqualTo(IssuanceStatus.PENDING_APPROVAL);

        // 승인은 ADMIN API로 — 감사 로그 actor 확인을 위해
        ResponseEntity<String> approve = postAdmin("/api/v1/admin/issuances/" + id + "/approve", adminToken);
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 승인 감사 로그: actor/before/after
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor, before_state::text AS before_state, after_state::text AS after_state
                FROM audit_log WHERE action = 'ISSUANCE_APPROVE' ORDER BY id DESC LIMIT 1
                """);
        assertThat(audit.get("actor")).isEqualTo("999999");
        assertThat((String) audit.get("before_state")).contains(String.valueOf(id));
        assertThat((String) audit.get("after_state")).contains("PENDING_APPROVAL").contains("APPROVED");

        // IS-06: 스케줄러가 start_at 도달 건을 SUBSCRIBING으로 전환 (test 프로필 500ms 주기)
        IssuanceStatus status = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            status = issuanceService.get(id).status();
            if (status == IssuanceStatus.SUBSCRIBING) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(status).isEqualTo(IssuanceStatus.SUBSCRIBING);

        assertThat(postAdmin("/api/v1/admin/issuances/" + id + "/start-allotment", adminToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(postAdmin("/api/v1/admin/issuances/" + id + "/list", adminToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(issuanceService.get(id).status()).isEqualTo(IssuanceStatus.LISTED);

        // 상장 시 발행인 계좌에 전량 발행 — INV-1 유지
        assertThat(ledger.totalIssued(symbol)).isEqualTo(Units.of(1_000));
        assertThat(ledger.balanceOf(symbol, OwnerId.of(issuer.id())).units()).isEqualTo(Units.of(1_000));
        assertThat(ledger.verifyInvariant(symbol).valid()).isTrue();
    }

    @Test
    @DisplayName("허용되지 않은 전이(DRAFT→APPROVED) API 시도 → 409 STATE_INVALID_TRANSITION")
    void invalidTransitionViaApi() throws Exception {
        var issuer = auth.signupAndLogin("issuer2");
        UnderlyingAsset asset = assetService.create(issuer.id(), "전이 테스트",
                UnderlyingAsset.AssetType.ETF, "TRNS", null, 10, null);
        var created = issuanceService.create(asset.id(), 100, 100,
                Instant.now().plusSeconds(86_400), Instant.now().plusSeconds(172_800));

        ResponseEntity<String> response = postAdmin(
                "/api/v1/admin/issuances/" + created.issuanceId() + "/approve", auth.adminToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).path("error").path("code").asText())
                .isEqualTo("STATE_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("일반 투자자 토큰으로 ADMIN 전이 시도 → 403 AUTH_FORBIDDEN")
    void adminEndpointRequiresAdminRole() throws Exception {
        var user = auth.signupAndLogin("not-admin");
        ResponseEntity<String> response = postAdmin("/api/v1/admin/issuances/1/approve", user.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(objectMapper.readTree(response.getBody()).path("error").path("code").asText())
                .isEqualTo("AUTH_FORBIDDEN");
    }

    private ResponseEntity<String> postAdmin(String url, String token) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(auth.bearer(token)), String.class);
    }
}

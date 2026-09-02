package com.fracta.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

/**
 * 발행 목록 API (IS-08) — 투자자 홈이 "청약 중"과 "상장"을 나눠 보여주는 근거다.
 * 이 엔드포인트가 없으면 홈 화면(FSD §11.2)을 만들 수 없다.
 */
class IssuanceListApiTest extends IntegrationTestBase {

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

    @Test
    @DisplayName("상태로 걸러 목록을 받고, 각 건에 자산명과 위험등급이 함께 실린다")
    void listsIssuancesFilteredByStatus() throws Exception {
        var issuer = auth.signupAndLogin("list-issuer");

        UnderlyingAsset asset = assetService.create(issuer.id(), "목록 테스트 자산",
                UnderlyingAsset.AssetType.REIT, "LIST", null, 100, "설명");
        var draft = issuanceService.create(asset.id(), 1_000, 1_000,
                Instant.now().plusSeconds(3_600), Instant.now().plusSeconds(7_200));

        JsonNode all = get("/api/v1/issuances", issuer.token());
        JsonNode row = findBySymbol(all, draft.tokenSymbol());

        assertThat(row).as("만든 발행이 목록에 있어야 한다").isNotNull();
        assertThat(row.path("assetName").asText()).isEqualTo("목록 테스트 자산");
        assertThat(row.path("assetType").asText()).isEqualTo("REIT");
        assertThat(row.path("status").asText()).isEqualTo("DRAFT");
        assertThat(row.path("riskGrade").asInt()).isBetween(1, 5);
        assertThat(row.path("unitPrice").asLong()).isEqualTo(1_000);
        assertThat(row.path("totalUnits").asLong()).isEqualTo(1_000);

        // 다른 상태로 거르면 빠져야 한다
        JsonNode subscribing = get("/api/v1/issuances?status=SUBSCRIBING", issuer.token());
        assertThat(findBySymbol(subscribing, draft.tokenSymbol()))
                .as("DRAFT 건이 SUBSCRIBING 필터에 섞이면 안 된다")
                .isNull();
    }

    @Test
    @DisplayName("status를 여러 개 주면 합집합으로 받는다 — 홈이 청약 중과 상장을 한 번에 읽는다")
    void acceptsMultipleStatuses() throws Exception {
        var issuer = auth.signupAndLogin("multi-status-issuer");
        UnderlyingAsset asset = assetService.create(issuer.id(), "복수 상태 자산",
                UnderlyingAsset.AssetType.ETF, "MULT", null, 100, null);

        var draft = issuanceService.create(asset.id(), 1_000, 1_000,
                Instant.now().plusSeconds(3_600), Instant.now().plusSeconds(7_200));
        var pending = issuanceService.create(asset.id(), 1_000, 1_000,
                Instant.now().plusSeconds(3_600), Instant.now().plusSeconds(7_200));
        issuanceService.submit(pending.issuanceId());

        JsonNode both = get("/api/v1/issuances?status=DRAFT,PENDING_APPROVAL", issuer.token());

        assertThat(findBySymbol(both, draft.tokenSymbol())).isNotNull();
        assertThat(findBySymbol(both, pending.tokenSymbol())).isNotNull();
        List<String> statuses = both.findValuesAsText("status");
        assertThat(statuses).isNotEmpty().allMatch(s -> s.equals("DRAFT") || s.equals("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("인증 없이는 목록을 볼 수 없다")
    void requiresAuthentication() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/issuances", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode get(String url, String token) throws Exception {
        ResponseEntity<String> response = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(auth.bearer(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private static JsonNode findBySymbol(JsonNode list, String tokenSymbol) {
        for (JsonNode node : list) {
            if (tokenSymbol.equals(node.path("tokenSymbol").asText())) {
                return node;
            }
        }
        return null;
    }
}

package com.fracta.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.common.error.ErrorCode;
import com.fracta.common.money.AmountOverflowException;
import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.IssuanceValidationException;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

/** IS-02 발행 검증 — 경계값·오버플로우·괴리율 경고. */
class IssuanceValidationTest extends IntegrationTestBase {

    @Autowired
    AssetService assetService;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    private long assetId;
    private long issuerId;

    private final Instant start = Instant.now().plusSeconds(86_400);
    private final Instant end = Instant.now().plusSeconds(172_800);

    @BeforeEach
    void setUp() {
        var issuer = auth.signupAndLogin("valid-issuer");
        issuerId = issuer.id();
        assetId = assetService.create(issuerId, "검증용 자산",
                UnderlyingAsset.AssetType.REAL_ESTATE, null, null, 100, null).id();
    }

    @Test
    @DisplayName("total_units 경계값: 99 거부 / 100 허용 / 1,000,000 허용 / 1,000,001 거부")
    void totalUnitsBoundaries() {
        assertThatThrownBy(() -> issuanceService.create(assetId, 99, 100, start, end))
                .isInstanceOf(IssuanceValidationException.class)
                .satisfies(e -> assertThat(((IssuanceValidationException) e).errorCode())
                        .isEqualTo(ErrorCode.VALID_UNITS_RANGE));

        assertThatCode(() -> issuanceService.create(assetId, 100, 100, start, end))
                .doesNotThrowAnyException();
        assertThatCode(() -> issuanceService.create(assetId, 1_000_000, 100, start, end))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> issuanceService.create(assetId, 1_000_001, 100, start, end))
                .isInstanceOf(IssuanceValidationException.class);
    }

    @Test
    @DisplayName("unit_price 99원 거부, 발행 총액 100억 초과 거부")
    void priceAndTotalAmountRules() {
        assertThatThrownBy(() -> issuanceService.create(assetId, 100, 99, start, end))
                .isInstanceOf(IssuanceValidationException.class)
                .satisfies(e -> assertThat(((IssuanceValidationException) e).errorCode())
                        .isEqualTo(ErrorCode.VALID_INVALID_INPUT));

        // 1,000,000 × 10,000 = 100억 — 허용 (경계)
        assertThatCode(() -> issuanceService.create(assetId, 1_000_000, 10_000, start, end))
                .doesNotThrowAnyException();
        // 1,000,000 × 10,001 = 100억 초과 — 거부
        assertThatThrownBy(() -> issuanceService.create(assetId, 1_000_000, 10_001, start, end))
                .isInstanceOf(IssuanceValidationException.class);
    }

    @Test
    @DisplayName("곱셈 오버플로우 유발 입력 → 400 VALID_AMOUNT_OVERFLOW (앱 크래시 아님)")
    void overflowInputReturnsValidError() throws Exception {
        // 서비스 레벨: Math.multiplyExact가 곱셈 시점에 잡는다
        assertThatThrownBy(() -> issuanceService.create(assetId, 100, Long.MAX_VALUE, start, end))
                .isInstanceOf(AmountOverflowException.class);

        // API 레벨: 공통 실패 포맷으로 400
        var user = auth.signupAndLogin("overflow-user");
        ResponseEntity<String> response = rest.exchange("/api/v1/issuances", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "assetId", assetId,
                        "totalUnits", 100,
                        "unitPrice", Long.MAX_VALUE,
                        "subscriptionStartAt", start.toString(),
                        "subscriptionEndAt", end.toString()), auth.bearer(user.token())),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).path("error").path("code").asText())
                .isEqualTo("VALID_AMOUNT_OVERFLOW");
    }

    @Test
    @DisplayName("티커 지정 + 괴리율 ±30% 초과 → 경고(차단 아님) / 이내면 경고 없음")
    void premiumWarning() {
        // Mock 어댑터: "MOCK-10000" → 기준가 10,000원(±1%), 분할비율 100 → 참조가 ≈ 100원
        long tickeredAsset = assetService.create(issuerId, "티커 자산",
                UnderlyingAsset.AssetType.REIT, null, "MOCK-10000", 100, null).id();

        var warned = issuanceService.create(tickeredAsset, 1_000, 200, start, end); // ≈ +100%
        assertThat(warned.warnings()).hasSize(1);
        assertThat(warned.warnings().getFirst()).contains("괴리");

        var clean = issuanceService.create(tickeredAsset, 1_000, 100, start, end);  // ≈ 0%
        assertThat(clean.warnings()).isEmpty();
    }
}

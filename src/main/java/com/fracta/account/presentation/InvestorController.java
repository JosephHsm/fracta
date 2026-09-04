package com.fracta.account.presentation;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.account.api.RiskGrade;
import com.fracta.account.api.SuitabilityResult;
import com.fracta.account.api.SuitabilityScope;
import com.fracta.account.application.RiskProfileService;
import com.fracta.account.application.SuitabilityService;
import com.fracta.common.money.Money;
import com.fracta.common.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/investors/me")
public class InvestorController {

    public record RiskProfileRequest(
            @NotNull @Size(min = 8, max = 8) List<@NotNull @Min(1) @Max(5) Integer> answers) {
    }

    /**
     * 부적합 확인 서명 요청. <b>어떤 상품에 대한 서명인지</b>를 반드시 함께 받는다 —
     * 서명은 그 상품에서만 효력이 있다.
     *
     * @param scopeType 청약이면 {@code ISSUANCE}, 유통이면 {@code TOKEN}
     * @param scopeId   발행 건 ID 또는 토큰 심볼
     */
    public record SuitabilityAckRequest(@NotNull @Min(1) @Max(5) Integer productGrade,
                                        @NotNull SuitabilityScope.Type scopeType,
                                        @NotBlank String scopeId) {
    }

    /**
     * 아래 응답 record들은 기존 {@code Map<String,Object>} 응답을 같은 필드명·같은 JSON으로
     * 옮긴 것이다. 스펙에 타입이 실려야 프론트 생성 클라이언트가 필드 변경을 컴파일 시점에 잡는다.
     */
    public record MeResponse(long investorId, String name, String kycStatus, int riskGrade,
                             BigDecimal cashBalance) {
    }

    public record RiskProfileResponse(int score, int grade, String gradeName, String expiresAt) {
    }

    public record SuitabilityAckResponse(boolean acked, int productGrade,
                                         SuitabilityScope.Type scopeType, String scopeId,
                                         String expiresAt) {
    }

    private final AccountQueryPort accountQuery;
    private final RiskProfileService riskProfileService;
    private final SuitabilityService suitabilityService;

    public InvestorController(AccountQueryPort accountQuery, RiskProfileService riskProfileService,
                              SuitabilityService suitabilityService) {
        this.accountQuery = accountQuery;
        this.riskProfileService = riskProfileService;
        this.suitabilityService = suitabilityService;
    }

    @GetMapping
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        InvestorId id = investorIdOf(jwt);
        var summary = accountQuery.findInvestor(id)
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + id.value()));
        Money cash = accountQuery.cashBalanceOf(id);
        return ApiResponse.of(new MeResponse(id.value(), summary.name(),
                summary.kycStatus().name(),
                summary.riskGrade() == null ? 0 : summary.riskGrade().level(),
                cash.toDisplay()));
    }

    @PostMapping("/risk-profile")
    public ApiResponse<RiskProfileResponse> submitRiskProfile(@AuthenticationPrincipal Jwt jwt,
                                                              @Valid @RequestBody RiskProfileRequest request) {
        var result = riskProfileService.submit(investorIdOf(jwt), request.answers());
        return ApiResponse.of(new RiskProfileResponse(result.score(), result.grade().level(),
                result.grade().koreanName(), result.expiresAt().toString()));
    }

    /**
     * 적합성 사전 점검 — 차단이면 403 SUIT_* 에러로 응답한다.
     * 어떤 상품에 대한 판정인지 함께 받는다. 부적합 확인 서명이 그 범위에서만 유효하기 때문이다.
     */
    @GetMapping("/suitability")
    public ApiResponse<SuitabilityResult> checkSuitability(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("productGrade") int productGrade,
            @RequestParam("scopeType") SuitabilityScope.Type scopeType,
            @RequestParam("scopeId") String scopeId) {
        return ApiResponse.of(suitabilityService.checkOrThrow(
                investorIdOf(jwt), RiskGrade.fromLevel(productGrade),
                new SuitabilityScope(scopeType, scopeId)));
    }

    @PostMapping("/suitability-ack")
    public ApiResponse<SuitabilityAckResponse> acknowledge(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody SuitabilityAckRequest request) {
        SuitabilityScope scope = new SuitabilityScope(request.scopeType(), request.scopeId());
        var expiresAt = suitabilityService.acknowledge(
                investorIdOf(jwt), RiskGrade.fromLevel(request.productGrade()), scope);
        return ApiResponse.of(new SuitabilityAckResponse(true, request.productGrade(),
                scope.type(), scope.id(), expiresAt.toString()));
    }

    static InvestorId investorIdOf(Jwt jwt) {
        return InvestorId.of(Long.parseLong(jwt.getSubject()));
    }
}

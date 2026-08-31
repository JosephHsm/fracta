package com.fracta.account.presentation;

import java.util.List;
import java.util.Map;

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
import com.fracta.account.application.RiskProfileService;
import com.fracta.account.application.SuitabilityService;
import com.fracta.common.money.Money;
import com.fracta.common.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/investors/me")
public class InvestorController {

    public record RiskProfileRequest(
            @NotNull @Size(min = 8, max = 8) List<@NotNull @Min(1) @Max(5) Integer> answers) {
    }

    public record SuitabilityAckRequest(@NotNull @Min(1) @Max(5) Integer productGrade) {
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
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal Jwt jwt) {
        InvestorId id = investorIdOf(jwt);
        var summary = accountQuery.findInvestor(id)
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + id.value()));
        Money cash = accountQuery.cashBalanceOf(id);
        return ApiResponse.of(Map.of(
                "investorId", id.value(),
                "name", summary.name(),
                "kycStatus", summary.kycStatus().name(),
                "riskGrade", summary.riskGrade() == null ? 0 : summary.riskGrade().level(),
                "cashBalance", cash.toDisplay()));
    }

    @PostMapping("/risk-profile")
    public ApiResponse<Map<String, Object>> submitRiskProfile(@AuthenticationPrincipal Jwt jwt,
                                                              @Valid @RequestBody RiskProfileRequest request) {
        var result = riskProfileService.submit(investorIdOf(jwt), request.answers());
        return ApiResponse.of(Map.of(
                "score", result.score(),
                "grade", result.grade().level(),
                "gradeName", result.grade().koreanName(),
                "expiresAt", result.expiresAt().toString()));
    }

    /** 적합성 사전 점검 — 차단이면 403 SUIT_* 에러로 응답한다. */
    @GetMapping("/suitability")
    public ApiResponse<SuitabilityResult> checkSuitability(@AuthenticationPrincipal Jwt jwt,
                                                           @RequestParam("productGrade") int productGrade) {
        return ApiResponse.of(suitabilityService.checkOrThrow(
                investorIdOf(jwt), RiskGrade.fromLevel(productGrade)));
    }

    @PostMapping("/suitability-ack")
    public ApiResponse<Map<String, Object>> acknowledge(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody SuitabilityAckRequest request) {
        suitabilityService.acknowledge(investorIdOf(jwt), RiskGrade.fromLevel(request.productGrade()));
        return ApiResponse.of(Map.of("acked", true, "productGrade", request.productGrade()));
    }

    static InvestorId investorIdOf(Jwt jwt) {
        return InvestorId.of(Long.parseLong(jwt.getSubject()));
    }
}

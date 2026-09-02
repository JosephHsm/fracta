package com.fracta.issuance.presentation;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fracta.common.response.ApiResponse;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.application.ProspectusService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.domain.UnderlyingAsset;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/issuances")
public class IssuanceController {

    public record CreateIssuanceRequest(
            @NotNull Long assetId,
            @NotNull Long totalUnits,
            @NotNull Long unitPrice,
            @NotNull Instant subscriptionStartAt,
            @NotNull Instant subscriptionEndAt) {
    }

    /** 투자설명서 업로드 결과. */
    public record ProspectusUploadResponse(String fileKey) {
    }

    /**
     * 목록 한 건. 투자자 홈이 자산명·위험등급·청약 마감까지 한 화면에 보여줘야 해서
     * 발행과 기초자산을 합쳐 내려준다.
     */
    public record IssuanceSummaryResponse(Long issuanceId, String tokenSymbol, String assetName,
                                          String assetType, long totalUnits, long unitPrice,
                                          long remainingUnits, IssuanceStatus status, int riskGrade,
                                          Issuance.AllotmentMethod allotmentMethod,
                                          String subscriptionStartAt, String subscriptionEndAt) {
    }

    /**
     * 발행 상세. 기존 {@code Map<String,Object>} 응답을 같은 필드명·같은 JSON으로 옮긴 것이다 —
     * 종목 상세 화면이 이 계약에 의존하므로 타입이 스펙에 실려야 한다.
     */
    public record IssuanceDetailResponse(Long issuanceId, String tokenSymbol, long totalUnits,
                                         long unitPrice, long remainingUnits, IssuanceStatus status,
                                         String prospectusFileKey) {
    }

    private final IssuanceService issuanceService;
    private final ProspectusService prospectusService;

    public IssuanceController(IssuanceService issuanceService, ProspectusService prospectusService) {
        this.issuanceService = issuanceService;
        this.prospectusService = prospectusService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssuanceService.CreateResult> create(@Valid @RequestBody CreateIssuanceRequest request) {
        return ApiResponse.of(issuanceService.create(request.assetId(), request.totalUnits(),
                request.unitPrice(), request.subscriptionStartAt(), request.subscriptionEndAt()));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<IssuanceService.TransitionResult> submit(@PathVariable("id") long id) {
        return ApiResponse.of(issuanceService.submit(id));
    }

    @PostMapping("/{id}/prospectus")
    public ApiResponse<ProspectusUploadResponse> uploadProspectus(@PathVariable("id") long id,
                                                                  @RequestParam("file") MultipartFile file)
            throws IOException {
        String fileKey = prospectusService.upload(id, file.getOriginalFilename(),
                file.getBytes(), file.getContentType());
        return ApiResponse.of(new ProspectusUploadResponse(fileKey));
    }

    /**
     * 발행 목록 (IS-08). {@code status}를 쉼표로 여러 개 줄 수 있다 —
     * 홈 화면이 "청약 중"과 "상장"을 한 번에 받아 두 묶음으로 나눈다.
     */
    @Operation(operationId = "listIssuances", summary = "발행 목록")
    @GetMapping
    public ApiResponse<List<IssuanceSummaryResponse>> list(
            @RequestParam(value = "status", required = false) List<IssuanceStatus> statuses) {
        return ApiResponse.of(issuanceService.list(statuses == null ? List.of() : statuses).stream()
                .map(IssuanceController::toSummary)
                .toList());
    }

    private static IssuanceSummaryResponse toSummary(IssuanceService.IssuanceSummary summary) {
        Issuance issuance = summary.issuance();
        UnderlyingAsset asset = summary.asset();
        return new IssuanceSummaryResponse(issuance.id(), issuance.tokenSymbol(),
                asset == null ? null : asset.name(),
                asset == null ? null : asset.assetType().name(),
                issuance.totalUnits(), issuance.unitPrice(), issuance.remainingUnits(),
                issuance.status(), issuance.riskGrade(), issuance.allotmentMethod(),
                issuance.subscriptionStartAt().toString(),
                issuance.subscriptionEndAt().toString());
    }

    @Operation(operationId = "getIssuance", summary = "발행 상세")
    @GetMapping("/{id}")
    public ApiResponse<IssuanceDetailResponse> get(@PathVariable("id") long id) {
        Issuance issuance = issuanceService.get(id);
        return ApiResponse.of(new IssuanceDetailResponse(issuance.id(), issuance.tokenSymbol(),
                issuance.totalUnits(), issuance.unitPrice(), issuance.remainingUnits(),
                issuance.status(), issuance.prospectusFileKey()));
    }
}

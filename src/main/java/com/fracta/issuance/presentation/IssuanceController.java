package com.fracta.issuance.presentation;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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
    public ApiResponse<Map<String, Object>> uploadProspectus(@PathVariable("id") long id,
                                                             @RequestParam("file") MultipartFile file)
            throws IOException {
        String fileKey = prospectusService.upload(id, file.getOriginalFilename(),
                file.getBytes(), file.getContentType());
        return ApiResponse.of(Map.of("fileKey", fileKey));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable("id") long id) {
        Issuance issuance = issuanceService.get(id);
        Map<String, Object> body = new HashMap<>();
        body.put("issuanceId", issuance.id());
        body.put("tokenSymbol", issuance.tokenSymbol());
        body.put("totalUnits", issuance.totalUnits());
        body.put("unitPrice", issuance.unitPrice());
        body.put("remainingUnits", issuance.remainingUnits());
        body.put("status", issuance.status().name());
        body.put("prospectusFileKey", issuance.prospectusFileKey());
        return ApiResponse.of(body);
    }
}

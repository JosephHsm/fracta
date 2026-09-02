package com.fracta.issuance.presentation;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.response.ApiResponse;
import com.fracta.issuance.application.IssuanceService;

import io.swagger.v3.oas.annotations.Operation;

/** ADMIN 전용 발행 전이 — /api/v1/admin/** 은 SecurityConfig에서 ROLE_ADMIN을 요구한다. */
@RestController
@RequestMapping("/api/v1/admin/issuances")
public class AdminIssuanceController {

    private final IssuanceService issuanceService;

    public AdminIssuanceController(IssuanceService issuanceService) {
        this.issuanceService = issuanceService;
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<IssuanceService.TransitionResult> approve(@PathVariable("id") long id) {
        return ApiResponse.of(issuanceService.approve(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<IssuanceService.TransitionResult> reject(@PathVariable("id") long id) {
        return ApiResponse.of(issuanceService.reject(id));
    }

    @PostMapping("/{id}/open-subscription")
    public ApiResponse<IssuanceService.TransitionResult> openSubscription(@PathVariable("id") long id) {
        return ApiResponse.of(issuanceService.openSubscription(id));
    }

    @PostMapping("/{id}/start-allotment")
    public ApiResponse<IssuanceService.TransitionResult> startAllotment(@PathVariable("id") long id) {
        return ApiResponse.of(issuanceService.startAllotment(id));
    }

    @Operation(operationId = "listIssuanceOnMarket", summary = "상장")
    @PostMapping("/{id}/list")
    public ApiResponse<IssuanceService.TransitionResult> list(@PathVariable("id") long id) {
        return ApiResponse.of(issuanceService.listIssuance(id));
    }
}

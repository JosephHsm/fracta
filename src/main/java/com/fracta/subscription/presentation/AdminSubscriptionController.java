package com.fracta.subscription.presentation;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.response.ApiResponse;
import com.fracta.subscription.application.SubscriptionAllotmentService;

/** ADMIN 전용 — 배정 확정 수동 트리거 (배치 스케줄링은 Phase 9). */
@RestController
@RequestMapping("/api/v1/admin/issuances")
public class AdminSubscriptionController {

    private final SubscriptionAllotmentService allotmentService;

    public AdminSubscriptionController(SubscriptionAllotmentService allotmentService) {
        this.allotmentService = allotmentService;
    }

    @PostMapping("/{issuanceId}/finalize-allotment")
    public ApiResponse<SubscriptionAllotmentService.FinalizeResult> finalizeAllotment(
            @PathVariable("issuanceId") long issuanceId) {
        return ApiResponse.of(allotmentService.finalizeAllotment(issuanceId));
    }
}

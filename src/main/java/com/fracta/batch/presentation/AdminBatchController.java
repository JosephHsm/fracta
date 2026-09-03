package com.fracta.batch.presentation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.batch.application.ReconciliationLauncher;
import com.fracta.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

/** ADMIN 전용 배치 수동 실행. /api/v1/admin/** 은 SecurityConfig에서 ROLE_ADMIN을 요구한다. */
@RestController
@RequestMapping("/api/v1/admin/batch")
public class AdminBatchController {

    private final ReconciliationLauncher reconciliation;

    public AdminBatchController(ReconciliationLauncher reconciliation) {
        this.reconciliation = reconciliation;
    }

    /**
     * 일일 대사를 즉시 실행한다. 훼손이 의심될 때 자정까지 기다릴 수 없다.
     *
     * <p>동기 실행이라 응답이 올 때는 검사가 끝나 있다. 위반 상세는 조회 API가 아니라
     * `reconciliation_result` 테이블과 알림에 남는다 — 조사 대상이지 화면 데이터가 아니다.
     */
    @Operation(operationId = "runReconciliationNow", summary = "일일 대사 즉시 실행")
    @PostMapping("/reconciliation")
    public ApiResponse<ReconciliationLauncher.RunResult> runReconciliation() {
        return ApiResponse.of(reconciliation.runNow());
    }
}

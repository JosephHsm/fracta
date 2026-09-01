package com.fracta.ai.presentation;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.ai.IndexResult;
import com.fracta.ai.LlmPort;
import com.fracta.audit.api.Auditable;
import com.fracta.common.response.ApiResponse;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;

/**
 * 재인덱싱 (관리자 전용).
 *
 * <p>업로드 시 인덱싱은 이벤트로 자동 실행되지만, AI 서비스가 내려가 있었거나 청킹
 * 파라미터를 바꾼 경우 다시 돌려야 한다. 기존 청크를 지우고 새로 만든다.
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {

    private final LlmPort llmPort;
    private final IssuanceService issuanceService;

    public AdminAiController(LlmPort llmPort, IssuanceService issuanceService) {
        this.llmPort = llmPort;
        this.issuanceService = issuanceService;
    }

    @PostMapping("/issuances/{id}/index")
    @Auditable(action = "PROSPECTUS_REINDEX", targetType = "ISSUANCE", targetId = "#p0")
    public ApiResponse<IndexResult> reindex(@PathVariable("id") long issuanceId) {
        Issuance issuance = issuanceService.get(issuanceId);
        String fileKey = issuance.prospectusFileKey();
        if (fileKey == null || fileKey.isBlank()) {
            throw new IllegalStateException("투자설명서가 업로드되지 않은 발행이다: " + issuanceId);
        }
        return ApiResponse.of(llmPort.indexProspectus(issuanceId, fileKey));
    }
}

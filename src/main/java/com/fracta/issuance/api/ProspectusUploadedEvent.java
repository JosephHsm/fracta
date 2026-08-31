package com.fracta.issuance.api;

/** 투자설명서 업로드 완료 이벤트 — AI 인덱싱(Phase 8)이 AFTER_COMMIT으로 구독한다. */
public record ProspectusUploadedEvent(long issuanceId, String fileKey) {
}

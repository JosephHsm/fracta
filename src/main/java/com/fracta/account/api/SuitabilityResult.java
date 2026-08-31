package com.fracta.account.api;

/** 적합성 판정 결과 (AC-04). */
public record SuitabilityResult(
        Decision decision,
        RiskGrade productGrade,
        RiskGrade investorGrade   // 유효한 진단이 없으면 null
) {

    public enum Decision {
        /** 성향등급 ≥ 상품등급 */
        ALLOWED,
        /** 부적합하지만 확인 서명으로 허용 */
        ALLOWED_BY_ACK,
        /** 상품등급 > 성향등급, 서명 없음 → 차단 */
        BLOCKED_MISMATCH,
        /** 성향 진단 미실시 또는 만료 → 차단 */
        BLOCKED_NO_PROFILE
    }

    public boolean allowed() {
        return decision == Decision.ALLOWED || decision == Decision.ALLOWED_BY_ACK;
    }
}

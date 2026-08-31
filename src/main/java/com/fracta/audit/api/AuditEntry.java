package com.fracta.audit.api;

/** audit_log 한 건. beforeState/afterState는 마스킹이 끝난 JSON 문자열이다. */
public record AuditEntry(
        String actor,
        String action,
        String targetType,
        String targetId,
        String channel,
        String beforeState,
        String afterState,
        String requestId
) {
}

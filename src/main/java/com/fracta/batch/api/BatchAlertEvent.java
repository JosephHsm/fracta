package com.fracta.batch.api;

import java.time.Instant;

/** 실제 알림 채널 대신 발행하는 Phase 9 운영 경보 이벤트. */
public record BatchAlertEvent(
        String jobName,
        String severity,
        String invariantCode,
        String tokenSymbol,
        String detail,
        Long jobExecutionId,
        Instant occurredAt) {
}

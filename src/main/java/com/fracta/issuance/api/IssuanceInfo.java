package com.fracta.issuance.api;

import java.time.Instant;

/** 다른 모듈에 노출하는 발행 건 요약 — 엔티티 직접 참조 금지. */
public record IssuanceInfo(
        long id,
        String tokenSymbol,
        long totalUnits,
        long remainingUnits,
        long unitPrice,
        String status,
        String allotmentMethod,   // FCFS | PRORATA
        int riskGrade,
        Instant subscriptionStartAt,
        Instant subscriptionEndAt,
        long issuerId
) {

    public boolean subscribing() {
        return "SUBSCRIBING".equals(status);
    }

    public boolean fcfs() {
        return "FCFS".equals(allotmentMethod);
    }
}

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
        String allotmentMethod,   // FCFS | PRORATA | HYBRID
        int riskGrade,
        /** HYBRID 에서 균등 배분에 쓸 총량 비율(%). 다른 방식에서는 무시된다. */
        int equalAllotmentPercent,
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

    /** 균등 + 비례 혼합 배정인가. */
    public boolean hybrid() {
        return "HYBRID".equals(allotmentMethod);
    }
}

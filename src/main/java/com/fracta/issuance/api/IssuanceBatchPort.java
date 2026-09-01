package com.fracta.issuance.api;

import java.time.Instant;
import java.util.List;

/** Phase 9 배치가 청약 종료 대상을 조회하고 배정 상태로 전환하는 공개 포트. */
public interface IssuanceBatchPort {

    List<Long> findDueForAllotment(Instant now);

    /** SUBSCRIBING → ALLOTTING. 종료 시각 전이거나 다른 상태면 실패한다. */
    void startAllotment(long issuanceId, Instant now);
}

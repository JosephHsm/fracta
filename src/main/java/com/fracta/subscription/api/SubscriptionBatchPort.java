package com.fracta.subscription.api;

/** Phase 9가 기존 배정 트랜잭션을 호출하기 위한 공개 포트. */
public interface SubscriptionBatchPort {

    void finalizeAllotment(long issuanceId);
}

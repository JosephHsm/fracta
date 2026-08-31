package com.fracta.subscription.domain;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 분산락 획득 실패 — 조용히 삼키지 않는다 (중복 배정 방지). */
public class LockTimeoutException extends DomainException {

    public LockTimeoutException(String lockKey) {
        super(ErrorCode.STATE_LOCK_TIMEOUT, "분산락 획득 실패: " + lockKey, Map.of("lockKey", lockKey));
    }
}

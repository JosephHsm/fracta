package com.fracta.subscription.domain;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 동일 멱등성 키가 다른 투자자의 요청에 사용됐다. */
public class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException(String idempotencyKey) {
        super(ErrorCode.IDEM_KEY_CONFLICT,
                "동일 Idempotency-Key가 다른 요청에 이미 사용됐다",
                Map.of("idempotencyKey", idempotencyKey));
    }
}

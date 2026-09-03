package com.fracta.subscription.domain;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 청약 기간 외 또는 취소 불가 상태의 취소 시도. */
public class CancellationNotAllowedException extends DomainException {

    public CancellationNotAllowedException(long orderId, String reason) {
        super(ErrorCode.STATE_NOT_SUBSCRIBING,
                "청약을 취소할 수 없습니다: " + reason,
                Map.of("orderId", orderId, "reason", reason));
    }
}

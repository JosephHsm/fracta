package com.fracta.subscription.domain;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 본인 청약이 아닌 주문에 대한 접근. */
public class ForbiddenOrderAccessException extends DomainException {

    public ForbiddenOrderAccessException(long orderId) {
        super(ErrorCode.AUTH_FORBIDDEN, "본인의 청약만 처리할 수 있습니다.", Map.of("orderId", orderId));
    }
}

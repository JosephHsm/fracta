package com.fracta.account.api;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 예치금 부족. */
public class InsufficientCashException extends DomainException {

    public InsufficientCashException(long requested) {
        super(ErrorCode.FUND_INSUFFICIENT_CASH,
                "예치금이 부족합니다. 요청 금액=%d".formatted(requested),
                Map.of("requested", requested));
    }
}

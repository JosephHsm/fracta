package com.fracta.common.money;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 보유 수량보다 큰 수량을 차감하려 할 때 발생. */
public class InsufficientUnitsException extends DomainException {

    public InsufficientUnitsException(long available, long requested) {
        super(ErrorCode.FUND_INSUFFICIENT_UNITS,
                "보유 수량이 부족하다. 보유=%d, 요청=%d".formatted(available, requested),
                Map.of("available", available, "requested", requested));
    }
}

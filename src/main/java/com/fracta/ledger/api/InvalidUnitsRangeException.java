package com.fracta.ledger.api;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 원장 연산에 0 이하 수량이 들어온 경우. */
public class InvalidUnitsRangeException extends DomainException {

    public InvalidUnitsRangeException(long units) {
        super(ErrorCode.VALID_UNITS_RANGE,
                "원장 연산 수량은 1 이상이어야 한다: " + units,
                Map.of("units", units));
    }
}

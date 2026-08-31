package com.fracta.common.money;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** long 범위를 벗어나는 금액·수량 연산 시 발생. */
public class AmountOverflowException extends DomainException {

    public AmountOverflowException(String operation, ArithmeticException cause) {
        super(ErrorCode.VALID_AMOUNT_OVERFLOW,
                "연산 중 표현 가능한 범위를 초과했다: " + operation,
                Map.of("operation", operation),
                cause);
    }
}

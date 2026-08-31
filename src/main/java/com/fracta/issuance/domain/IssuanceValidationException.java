package com.fracta.issuance.domain;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 발행 계획 검증 실패 (IS-02). */
public class IssuanceValidationException extends DomainException {

    private IssuanceValidationException(ErrorCode code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    public static IssuanceValidationException unitsOutOfRange(long totalUnits) {
        return new IssuanceValidationException(ErrorCode.VALID_UNITS_RANGE,
                "총 조각 수는 100 이상 1,000,000 이하여야 한다: " + totalUnits,
                Map.of("totalUnits", totalUnits));
    }

    public static IssuanceValidationException priceTooLow(long unitPrice) {
        return new IssuanceValidationException(ErrorCode.VALID_INVALID_INPUT,
                "조각당 단가는 100원 이상이어야 한다: " + unitPrice,
                Map.of("unitPrice", unitPrice));
    }

    public static IssuanceValidationException totalAmountExceeded(long totalUnits, long unitPrice) {
        return new IssuanceValidationException(ErrorCode.VALID_INVALID_INPUT,
                "발행 총액은 100억원을 초과할 수 없다",
                Map.of("totalUnits", totalUnits, "unitPrice", unitPrice));
    }
}

package com.fracta.issuance.domain;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 허용되지 않은 발행 상태 전이. */
public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(IssuanceStatus from, IssuanceStatus to) {
        super(ErrorCode.STATE_INVALID_TRANSITION,
                "%s → %s 전이는 허용되지 않는다".formatted(from, to),
                Map.of("from", from.name(), "to", to.name()));
    }
}

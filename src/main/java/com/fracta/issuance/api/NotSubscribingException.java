package com.fracta.issuance.api;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 청약 가능 상태(SUBSCRIBING)가 아닌 발행 건에 대한 청약/취소 시도. */
public class NotSubscribingException extends DomainException {

    public NotSubscribingException(long issuanceId, String currentStatus) {
        super(ErrorCode.STATE_NOT_SUBSCRIBING,
                "청약 가능한 상태가 아니다. 현재 상태: " + currentStatus,
                Map.of("issuanceId", issuanceId, "status", currentStatus));
    }
}

package com.fracta.account.api;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 투자성향 진단 미실시 또는 만료. */
public class RiskProfileRequiredException extends DomainException {

    public RiskProfileRequiredException(InvestorId investorId) {
        super(ErrorCode.SUIT_PROFILE_REQUIRED,
                "유효한 투자성향 진단이 없습니다. 진단 후 다시 시도해 주세요.",
                Map.of("investorId", investorId.value()));
    }
}

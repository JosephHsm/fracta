package com.fracta.account.api;

import java.util.Optional;

import com.fracta.common.money.Money;

/** 다른 모듈이 투자자 정보를 조회하는 공개 포트 — 엔티티 직접 참조 금지. */
public interface AccountQueryPort {

    Optional<InvestorSummary> findInvestor(InvestorId id);

    Money cashBalanceOf(InvestorId id);

    boolean isKycVerified(InvestorId id);

    record InvestorSummary(InvestorId id, String name, KycStatus kycStatus, RiskGrade riskGrade) {
    }
}

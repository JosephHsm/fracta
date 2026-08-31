package com.fracta.account.api;

/**
 * 적합성 판정 포트 (AC-04). Phase 4 청약·Phase 6 매수가 호출한다.
 * 다른 모듈은 account 엔티티를 직접 참조하지 않고 반드시 이 포트를 경유한다.
 */
public interface SuitabilityPort {

    SuitabilityResult check(InvestorId investor, RiskGrade productGrade);
}

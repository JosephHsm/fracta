package com.fracta.account.api;

/**
 * 적합성 판정 포트 (AC-04). Phase 4 청약·Phase 6 매수가 호출한다.
 * 다른 모듈은 account 엔티티를 직접 참조하지 않고 반드시 이 포트를 경유한다.
 */
public interface SuitabilityPort {

    /**
     * @param scope 부적합 확인 서명이 적용될 상품 범위. 청약이면 발행 건, 유통이면 종목이다.
     *              서명은 이 범위 안에서만 효력이 있다
     */
    SuitabilityResult check(InvestorId investor, RiskGrade productGrade, SuitabilityScope scope);
}

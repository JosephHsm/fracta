package com.fracta.account.api;

import java.util.Optional;

import com.fracta.common.money.Money;

/** 다른 모듈이 투자자 정보를 조회하는 공개 포트 — 엔티티 직접 참조 금지. */
public interface AccountQueryPort {

    Optional<InvestorSummary> findInvestor(InvestorId id);

    Money cashBalanceOf(InvestorId id);

    boolean isKycVerified(InvestorId id);

    /** 전체 투자자 예치금 잔액 합 (INV-6 검증용). */
    long sumCashBalances();

    /** 외부 순유입 = Σ DEPOSIT − Σ WITHDRAW. 내부 이동(증거금 홀드/환불/정산)은 제외한다 (INV-6). */
    long externalNetDeposits();

    /** INV-6 위반 시 원인 추적용 — 투자자별 외부 순유입·현재 잔액. */
    java.util.List<CashPosition> cashPositions();

    /** INV-6 위반 시 원인 추적용 — 대금 이동 유형별 합계. 어느 흐름이 어긋났는지 좁힌다. */
    java.util.Map<String, Long> cashFlowByType();

    /**
     * @param unexplained 잔액 − Σ(대금 이동 기록). 0이 아니면 기록 없이 잔액이 변한 것이다
     */
    record CashPosition(long investorId, long externalNet, long cashBalance, long unexplained) {
    }

    record InvestorSummary(InvestorId id, String name, KycStatus kycStatus, RiskGrade riskGrade) {
    }
}

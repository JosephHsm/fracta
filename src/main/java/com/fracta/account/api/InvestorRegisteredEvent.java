package com.fracta.account.api;

/** 투자자 등록 완료 이벤트 — KYC Mock 처리기가 구독한다 (AFTER_COMMIT). */
public record InvestorRegisteredEvent(InvestorId investorId) {
}

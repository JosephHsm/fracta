package com.fracta.account.api;

import com.fracta.common.money.Money;

/**
 * 예치금 내부 이동 포트 — 청약(Phase 4)·결제(Phase 6)가 사용한다.
 * 호출자의 트랜잭션에 참여한다. 외부 입출금(deposit/withdraw)은 account 모듈 전용이다.
 */
public interface CashPort {

    /** 증거금 홀드 — 잔액 부족 시 InsufficientCashException. */
    void holdMargin(InvestorId investorId, Money amount);

    /** 증거금 환불. */
    void refundMargin(InvestorId investorId, Money amount);

    /** 배정 대금 발행인 귀속. */
    void settlementCredit(InvestorId investorId, Money amount);
}

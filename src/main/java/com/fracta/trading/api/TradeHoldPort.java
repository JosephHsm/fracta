package com.fracta.trading.api;

/**
 * 매수 주문이 잡고 있는 대금 홀드 조회 포트.
 *
 * <p>INV-6(예치금 보존)이 쓴다. 홀드는 투자자 잔액에서 이미 빠져 있으므로,
 * 청약 미결제 증거금과 똑같이 <b>보존식의 한 항</b>으로 더해 줘야 한다.
 * 안 더하면 매수 주문이 오더북에 올라 있는 동안 INV-6이 홀드만큼 깨진 것으로 보인다.
 */
public interface TradeHoldPort {

    /** 아직 환급되지 않은 매수 대금 홀드의 전체 합계. */
    long outstandingHeldAmount();
}

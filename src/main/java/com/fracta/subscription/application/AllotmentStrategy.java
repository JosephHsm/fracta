package com.fracta.subscription.application;

/**
 * 선착순(FCFS) 잔여 수량 예약 전략 (FSD §8.3). 세 구현 모두 항상 빈으로 등록되고
 * {@code subscription.concurrency} 설정이 활성 전략을 고른다.
 *
 * <p>계약: 부족 시 {@code InsufficientUnitsException},
 * SUBSCRIBING이 아니면 {@code NotSubscribingException}. 호출자의 트랜잭션에 참여한다.
 */
public interface AllotmentStrategy {

    String name();

    void reserve(long issuanceId, long units);

    void release(long issuanceId, long units);
}

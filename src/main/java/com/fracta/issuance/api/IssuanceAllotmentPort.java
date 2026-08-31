package com.fracta.issuance.api;

/**
 * 청약 모듈이 발행 건의 잔여 수량을 다루는 공개 포트.
 * 세 가지 예약 방식은 청약 동시성 전략(FSD §8.3)이 골라 쓴다.
 * 모든 메서드는 호출자의 트랜잭션에 참여한다.
 */
public interface IssuanceAllotmentPort {

    IssuanceInfo info(long issuanceId);

    /** 캐시를 거치지 않는 잔여 수량 조회 — 원자적 감소 직후 값이 필요할 때 쓴다. */
    long remainingUnits(long issuanceId);

    /**
     * 방식 C — DB 원자적 감소. 성공 시 true.
     * false면 상태가 SUBSCRIBING이 아니거나 잔여 수량 부족이다.
     */
    boolean atomicReserve(long issuanceId, long units);

    /** 방식 A — 비관적 락 하 검증·감소. 부족 시 InsufficientUnitsException, 상태 위반 시 NotSubscribingException. */
    void reserveWithPessimisticLock(long issuanceId, long units);

    /**
     * 방식 B 보조 — 락 없는 검증·감소. 반드시 외부 직렬화(Redis 분산락) 하에서만 호출한다.
     */
    void reserveGuardedExternally(long issuanceId, long units);

    /** 청약 취소 시 잔여 수량 복구. */
    void release(long issuanceId, long units);

    /** 배정 확정: PRORATA 잔여 확정 + LISTED 전이. */
    void settleAndList(long issuanceId, long soldUnits);

    /** 발행인에게 배정 대금 귀속 시 필요한 발행인 ID 등은 info()로 조회한다. */
}

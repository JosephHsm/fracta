package com.fracta.subscription.api;

import java.util.List;

import com.fracta.common.invariant.ReconciliationCheck;

/**
 * 배정·예치금 불변식 검증 포트 (INV-5, INV-6, 그리고 현금 INV-3).
 *
 * <p>야간 대사 배치가 이걸 부른다. 예전에는 배치가 같은 검증을 자기 SQL로 따로 갖고 있어
 * <b>정의가 두 벌</b>이었고, 한쪽만 고치면 검증기가 서로 다른 답을 냈다 — 실제로 INV-6에
 * 매수 대금 홀드 항을 추가할 때 그 일이 일어나 야간 배치가 정상 상태를 위반으로 판정했다.
 *
 * <p>불변식의 정의는 그 불변식을 소유한 모듈에만 둔다. 배치는 모아서 기록·경보만 한다.
 */
public interface InvariantCheckPort {

    /** INV-5 — 이 종목의 배정 총량. 배정이 끝나지 않은 발행 건이면 검증 대상이 아니다. */
    ReconciliationCheck checkInv5(String tokenSymbol);

    /**
     * 전역 검증 — 현금 잔고 음수(INV-3)와 예치금 보존(INV-6).
     *
     * <p>보존식은 예치금 잔액·청약 증거금·매수 홀드를 각각 다른 테이블에서 더한다.
     * 나눠 읽으면 자금이 옮겨가는 중간을 보게 되므로 호출자가 한 스냅샷으로 묶어야 한다.
     */
    List<ReconciliationCheck> checkGlobal();
}

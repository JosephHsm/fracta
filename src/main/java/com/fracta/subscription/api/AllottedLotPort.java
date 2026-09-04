package com.fracta.subscription.api;

import java.time.Instant;
import java.util.List;

/**
 * 청약으로 배정받은 물량 조회 포트.
 *
 * <p>손익을 내려면 <b>얼마에 샀는지</b>가 필요한데, 조각을 얻는 경로가 청약과 매매 둘이다.
 * 원장({@code ledger_balance})은 수량만 갖고 있어 취득원가를 알 수 없다. 매매분은 체결
 * 기록에 남지만 청약분은 이 모듈에만 있어서, 포지션 계산이 여기를 경유한다.
 */
public interface AllottedLotPort {

    /**
     * 배정 한 건.
     *
     * @param unitPrice 배정 단가(= 발행 단가). 청약은 단일 가격이라 조각당 원가가 그대로 이 값이다
     */
    record AllottedLot(String tokenSymbol, long units, long unitPrice, Instant allottedAt) {
    }

    /** 이 투자자가 배정받은 물량 전부. 시간 오름차순. */
    List<AllottedLot> lotsOf(long investorId);
}

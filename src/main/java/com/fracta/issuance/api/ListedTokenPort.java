package com.fracta.issuance.api;

import java.util.Optional;

/**
 * 유통 모듈이 종목 정보를 조회하고 거래 정지를 요청하는 공개 포트.
 * trading 은 issuance 엔티티를 직접 참조하지 않는다.
 */
public interface ListedTokenPort {

    Optional<ListedToken> findByTokenSymbol(String tokenSymbol);

    /** 상장 종목 목록 (오픈 API 종목 조회). */
    java.util.List<ListedToken> listAll();

    /** 괴리율 20% 초과 시 자동 거래 중단 (TR-08). 이미 SUSPENDED면 아무것도 하지 않는다. */
    void suspend(String tokenSymbol, String reason);

    /**
     * @param tradable LISTED 상태여야 신규 주문을 받는다
     * @param brokerTicker 원자산 티커. 없으면 괴리율 계산을 건너뛴다
     * @param splitRatio 원자산 1주 = splitRatio 조각
     */
    record ListedToken(long issuanceId, String tokenSymbol, String status, boolean tradable,
                       String brokerTicker, long splitRatio, int riskGrade,
                       long unitPrice,
                       java.math.BigDecimal premiumWarnPercent,
                       java.math.BigDecimal premiumSuspendPercent) {
    }
}

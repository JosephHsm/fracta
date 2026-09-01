package com.fracta.trading.api;

/**
 * 유통 도메인 이벤트. 오픈 API(Phase 7)가 구독해 웹훅으로 내보낸다.
 *
 * <p>유통 모듈은 openapi 를 모른다 — 의존 방향은 {@code openapi → 도메인} 한쪽뿐이다 (FSD §3.2).
 */
public final class TradeEvents {

    private TradeEvents() {
    }

    public record OrderFilled(long orderId, String tokenSymbol, long investorId,
                              long filledUnits, long price) {
    }

    public record OrderPartiallyFilled(long orderId, String tokenSymbol, long investorId,
                                       long filledUnits, long remainingUnits, long price) {
    }

    public record OrderCancelled(long orderId, String tokenSymbol, long investorId,
                                 long cancelledUnits) {
    }
}

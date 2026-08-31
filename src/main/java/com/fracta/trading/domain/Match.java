package com.fracta.trading.domain;

/**
 * 매칭 결과 1건. 체결가는 <b>먼저 들어온 주문(메이커)의 가격</b>이다 (Price-Time Priority).
 * 신규 주문 가격을 쓰면 우선순위 규칙 위반이다.
 */
public record Match(long buyOrderId, long sellOrderId, long buyerId, long sellerId,
                    long price, long units) {

    public Match {
        if (price <= 0) {
            throw new IllegalArgumentException("체결가는 양수여야 한다: " + price);
        }
        if (units <= 0) {
            throw new IllegalArgumentException("체결량은 양수여야 한다: " + units);
        }
    }
}

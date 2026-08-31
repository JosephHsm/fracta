package com.fracta.trading.domain;

/** FSD §5.2 TradeOrder 상태 머신: OPEN → PARTIALLY_FILLED → FILLED / CANCELLED / REJECTED */
public enum OrderStatus {
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    /** 오더북에 남아 있어야 하는 상태 — 재시작 복원 대상이다. */
    public boolean isOpenOnBook() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }
}

package com.fracta.trading.domain;

/** LIMIT: 지정가. MARKET: 시장가 — 미체결 잔량은 큐에 넣지 않고 취소한다. */
public enum OrderType {
    LIMIT,
    MARKET
}

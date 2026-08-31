package com.fracta.external.broker;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * 증권사 시세 조회 포트 (FSD §9.1, 시그니처 고정). 조회 전용 — 주문 전송 금지.
 * Phase 3~4는 MockMarketDataAdapter, Phase 5에서 NamuhPlugMarketDataAdapter 추가.
 */
public interface MarketDataPort {

    Quote getCurrentPrice(String ticker);

    List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to);

    void subscribeRealtime(String ticker, Consumer<Tick> handler);

    void unsubscribe(String ticker);
}

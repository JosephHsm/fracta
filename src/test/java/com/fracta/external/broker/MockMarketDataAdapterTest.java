package com.fracta.external.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mock 시세 시뮬레이터의 계약.
 *
 * <p>핵심은 <b>일봉과 현재가가 같은 시계열</b>이라는 것이다. 예전에는 각각 독립적인
 * 랜덤워크라 화면에서 차트 오른쪽 끝과 괴리율 배지가 다른 값을 가리켰다.
 */
class MockMarketDataAdapterTest {

    private final MockMarketDataAdapter adapter = new MockMarketDataAdapter();

    @Test
    @DisplayName("마지막 봉의 종가는 현재가와 같다 — 차트 오른쪽 끝과 괴리율이 어긋나지 않는다")
    void lastCandleClosesAtCurrentPrice() {
        String ticker = "MOCK-1000000";
        // 현재가를 몇 번 움직여 기준가에서 떨어뜨린다
        for (int i = 0; i < 20; i++) {
            adapter.getCurrentPrice(ticker);
        }
        long current = adapter.getCurrentPrice(ticker).price().amount();

        LocalDate to = LocalDate.of(2026, 9, 3);
        List<Candle> candles = adapter.getDailyCandles(ticker, to.minusDays(29), to);

        assertThat(candles).hasSize(30);
        assertThat(candles.get(candles.size() - 1).close().amount())
                .as("마지막 봉 종가 = 현재가")
                .isEqualTo(current);
        assertThat(candles.get(candles.size() - 1).date()).isEqualTo(to);
        assertThat(candles.get(0).date()).isEqualTo(to.minusDays(29));
    }

    @Test
    @DisplayName("모든 봉이 high >= max(open, close) >= min(open, close) >= low 를 지킨다")
    void candlesAreWellFormed() {
        LocalDate to = LocalDate.of(2026, 9, 3);
        for (Candle candle : adapter.getDailyCandles("MOCK-50000", to.minusDays(9), to)) {
            long high = candle.high().amount();
            long low = candle.low().amount();
            long open = candle.open().amount();
            long close = candle.close().amount();

            assertThat(high).isGreaterThanOrEqualTo(Math.max(open, close));
            assertThat(low).isLessThanOrEqualTo(Math.min(open, close));
            assertThat(low).isPositive();
        }
    }

    @Test
    @DisplayName("from이 to보다 뒤면 빈 목록 — 예외를 던지지 않는다")
    void reversedRangeReturnsEmpty() {
        LocalDate day = LocalDate.of(2026, 9, 3);
        assertThat(adapter.getDailyCandles("MOCK-10000", day, day.minusDays(1))).isEmpty();
    }
}

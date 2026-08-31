package com.fracta.external.broker;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fracta.common.money.Money;

/**
 * 랜덤워크 시세 시뮬레이터. Phase 1~4 및 CI 전 구간의 기본 어댑터
 * (Phase 5에서 NamuhPlugMarketDataAdapter가 주력으로 추가된다).
 *
 * <p>기준가는 티커에 포함된 숫자(예: "MOCK-4200" → 4,200원), 숫자가 없으면 10,000원.
 * 호출마다 ±1% 이내로 움직인다 — 테스트가 기준가를 티커로 제어할 수 있다.
 */
@Component
public class MockMarketDataAdapter implements MarketDataPort {

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");
    private static final long DEFAULT_BASE_PRICE = 10_000;

    private final ConcurrentHashMap<String, Long> lastPrices = new ConcurrentHashMap<>();

    @Override
    public Quote getCurrentPrice(String ticker) {
        long price = lastPrices.compute(ticker, (t, last) -> {
            long base = last != null ? last : basePriceOf(t);
            long delta = Math.round(base * (ThreadLocalRandom.current().nextDouble(-0.01, 0.01)));
            return Math.max(1, base + delta);
        });
        return new Quote(ticker, Money.of(price), Instant.now());
    }

    @Override
    public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
        List<Candle> candles = new ArrayList<>();
        long price = basePriceOf(ticker);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long close = Math.max(1, price + Math.round(price * ThreadLocalRandom.current().nextDouble(-0.02, 0.02)));
            candles.add(new Candle(d, Money.of(price), Money.of(Math.max(price, close)),
                    Money.of(Math.min(price, close)), Money.of(close), ThreadLocalRandom.current().nextLong(1_000, 100_000)));
            price = close;
        }
        return candles;
    }

    @Override
    public void subscribeRealtime(String ticker, Consumer<Tick> handler) {
        // Mock — 실시간 스트림 없음 (Phase 5)
    }

    @Override
    public void unsubscribe(String ticker) {
        // Mock — no-op
    }

    private long basePriceOf(String ticker) {
        Matcher m = DIGITS.matcher(ticker);
        if (m.find()) {
            try {
                long parsed = Long.parseLong(m.group(1));
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 아래 기본값 사용
            }
        }
        return DEFAULT_BASE_PRICE;
    }
}

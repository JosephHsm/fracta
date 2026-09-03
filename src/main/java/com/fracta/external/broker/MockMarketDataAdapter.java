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

    /**
     * 일봉 시뮬레이션. <b>마지막 봉의 종가는 현재가와 일치시킨다.</b>
     *
     * <p>예전에는 기준가에서 앞으로 걸었는데, 그러면 현재가(getCurrentPrice)와 무관한
     * 별개의 랜덤워크가 나온다. 화면에서 차트 오른쪽 끝과 괴리율 배지가 서로 다른 값을
     * 가리켜 "차트는 11,361원인데 괴리율은 +2.69%?"가 된다. 실제 시세 피드는 그렇지 않다.
     * 그래서 현재가를 기점으로 <b>과거 방향으로</b> 걸어 시계열을 만든다.
     */
    @Override
    public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return List.of();
        }
        // 현재가를 오른쪽 끝에 고정한다. 아직 조회된 적이 없으면 기준가가 곧 현재가다.
        long anchor = lastPrices.getOrDefault(ticker, basePriceOf(ticker));

        List<Long> closes = new ArrayList<>();
        long price = anchor;
        for (LocalDate d = to; !d.isBefore(from); d = d.minusDays(1)) {
            closes.add(price);
            price = Math.max(1, price - Math.round(price * ThreadLocalRandom.current().nextDouble(-0.02, 0.02)));
        }
        java.util.Collections.reverse(closes);

        List<Candle> candles = new ArrayList<>();
        LocalDate day = from;
        long open = closes.get(0);
        for (long close : closes) {
            candles.add(new Candle(day, Money.of(open), Money.of(Math.max(open, close)),
                    Money.of(Math.min(open, close)), Money.of(close),
                    ThreadLocalRandom.current().nextLong(1_000, 100_000)));
            open = close;
            day = day.plusDays(1);
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

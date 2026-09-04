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
 * <p><b>기준가는 종목마스터의 전일종가를 먼저 본다.</b> 실제 종목코드(예: {@code 069500})면
 * 진짜 ETF 가격 근처에서 출발하므로, mock 으로 만든 시드를 plug 프로파일에서 열거나 그 반대로
 * 해도 괴리율이 터지지 않는다. 예전에는 티커 숫자만 봐서 {@code 365550} 이 365,550원이 됐고,
 * 실제 3,000원대인 종목과 100배 넘게 벌어져 기동하자마자 자동 거래중단이 걸렸다.
 *
 * <p>마스터에 없는 티커는 예전 규칙을 그대로 쓴다 — 숫자가 있으면 그 값(예: "MOCK-4200" →
 * 4,200원), 없으면 10,000원. 테스트가 기준가를 티커로 제어하는 경로는 유지된다.
 *
 * <p>어느 쪽이든 호출마다 ±1% 이내로 움직인다.
 */
@Component
public class MockMarketDataAdapter implements MarketDataPort {

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");
    private static final long DEFAULT_BASE_PRICE = 10_000;

    private final ConcurrentHashMap<String, Long> lastPrices = new ConcurrentHashMap<>();

    /**
     * 종목마스터 조회. 마스터가 적재되지 않은 환경(단위 테스트 등)에서도 동작해야 하므로
     * 지연 참조로 받는다 — 없으면 티커 숫자 규칙으로 떨어진다.
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.fracta.external.broker.instrument.InstrumentMasterRepository> instrumentMasters;

    @org.springframework.beans.factory.annotation.Autowired
    public MockMarketDataAdapter(
            org.springframework.beans.factory.ObjectProvider<
                    com.fracta.external.broker.instrument.InstrumentMasterRepository> instrumentMasters) {
        this.instrumentMasters = instrumentMasters;
    }

    /**
     * 종목마스터 없이 쓰는 생성자 — 기준가는 티커 숫자 규칙만 따른다.
     * 마스터를 띄우지 않는 단위 테스트가 쓴다.
     */
    public MockMarketDataAdapter() {
        this(null);
    }

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
        Long fromMaster = prevCloseOf(ticker);
        if (fromMaster != null && fromMaster > 0) {
            return fromMaster;
        }
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

    /** 종목마스터의 전일종가. 마스터가 없거나 종목이 없으면 null. */
    private Long prevCloseOf(String ticker) {
        if (instrumentMasters == null) {
            return null;
        }
        var repository = instrumentMasters.getIfAvailable();
        if (repository == null) {
            return null;
        }
        try {
            return repository.findById(ticker)
                    .map(com.fracta.external.broker.instrument.InstrumentMaster::prevClose)
                    .orElse(null);
        } catch (RuntimeException e) {
            // 마스터 조회 실패가 시세 시뮬레이터를 멈추게 두지 않는다
            return null;
        }
    }
}

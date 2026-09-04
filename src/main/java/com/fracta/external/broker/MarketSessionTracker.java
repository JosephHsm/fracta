package com.fracta.external.broker;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 거래소별 장 상태 관측기.
 *
 * <p>시세를 받아올 때마다 <b>체결일자와 누적거래량</b>을 여기에 넘긴다. 거래량이 직전 관측보다
 * 늘었으면 장이 돌고 있는 것이고, 그대로면 멈춘 것이다. 시간표를 코드에 박지 않으므로
 * 공휴일·임시휴장·조기폐장·서머타임이 저절로 맞는다.
 *
 * <p>한 거래소에 여러 종목이 있으므로 <b>종목별로 관측하고 거래소 단위로 합친다</b> —
 * 어느 한 종목이라도 거래량이 늘면 그 거래소는 열려 있다. 거래가 없는 종목 하나 때문에
 * 시장 전체를 마감으로 보면 안 된다.
 *
 * <p>거래량이 멈춘 뒤에도 바로 마감으로 뒤집지 않는다({@link #STALE_AFTER}). 한산한 종목은
 * 몇 분씩 체결이 없을 수 있어서, 그때마다 화면이 장중↔마감을 오가면 그게 더 이상하다.
 *
 * <p>거래량 비교는 두 번째 관측부터 답이 나온다. 그때까지 "확인 중"만 띄우면 부팅 직후 몇 분간
 * 화면이 비므로, <b>거래소가 준 시각·일자가 이미 지난 값이면 즉시 마감으로 본다.</b> 이것도
 * 시간표가 아니라 거래소가 알려준 사실을 쓰는 것이다 — 우리가 아는 건 "거래소의 마지막 시세가
 * 언제 것이냐"뿐이고, 그게 한참 전이면 장이 닫힌 것이다.
 */
@Component
public class MarketSessionTracker {

    /** 이 시간 동안 어느 종목도 거래량이 늘지 않으면 마감으로 본다. */
    static final java.time.Duration STALE_AFTER = java.time.Duration.ofMinutes(5);

    /** 거래소가 준 마지막 시세 시각이 이만큼 지났으면 마감으로 본다. */
    static final long QUOTE_STALE_MINUTES = 15;

    private record Observation(long volume, Instant changedAt, String quotedAt, String tradeDate) {
    }

    private final Map<String, Observation> byTicker = new ConcurrentHashMap<>();
    private final Map<MarketVenue, Instant> lastActivity = new ConcurrentHashMap<>();
    private final Map<MarketVenue, Observation> lastByVenue = new ConcurrentHashMap<>();

    private final java.time.Clock clock;

    public MarketSessionTracker() {
        this(java.time.Clock.systemUTC());
    }

    /** 시각을 고정해 만드는 생성자 — 테스트가 개장·마감 전이를 재현할 때 쓴다. */
    public MarketSessionTracker(java.time.Clock clock) {
        this.clock = clock;
    }

    /**
     * 시세 응답에서 읽은 값을 기록한다.
     *
     * @param volume    누적 거래량. 장중이면 늘고 마감이면 멈춘다
     * @param quotedAt  거래소가 준 마지막 시세 시각 표기. 없으면 null
     * @param tradeDate 거래소가 준 체결일자(yyyyMMdd). 없으면 null
     */
    public void observe(MarketVenue venue, String ticker, long volume, String quotedAt,
                        String tradeDate) {
        Instant now = clock.instant();
        Observation current = new Observation(volume, now, quotedAt, tradeDate);
        lastByVenue.put(venue, current);

        byTicker.compute(ticker, (key, previous) -> {
            if (previous == null) {
                return current;
            }
            if (volume > previous.volume()) {
                lastActivity.put(venue, now);
                return current;
            }
            // 거래량이 그대로다 — 변화 시각은 예전 것을 유지한다
            return new Observation(previous.volume(), previous.changedAt(), quotedAt, tradeDate);
        });
    }

    /** 이 거래소의 현재 장 상태. */
    public MarketSession sessionOf(MarketVenue venue) {
        Observation last = lastByVenue.get(venue);
        if (last == null) {
            return MarketSession.unknown(venue);
        }
        Instant now = clock.instant();
        Instant activeAt = lastActivity.get(venue);

        if (activeAt != null && !activeAt.isBefore(now.minus(STALE_AFTER))) {
            // 최근에 거래량이 늘었다 — 가장 확실한 개장 신호다
            return session(venue, MarketSession.State.OPEN, last, now);
        }
        if (quoteIsStale(venue, last, now)) {
            // 거래소가 준 시각·일자가 이미 지난 값이다
            return session(venue, MarketSession.State.CLOSED, last, now);
        }
        if (activeAt != null) {
            // 거래량이 한동안 멈췄다
            return session(venue, MarketSession.State.CLOSED, last, now);
        }
        boolean observedLongEnough = byTicker.values().stream()
                .anyMatch(o -> o.changedAt().isBefore(now.minus(STALE_AFTER)));
        return session(venue,
                observedLongEnough ? MarketSession.State.CLOSED : MarketSession.State.UNKNOWN,
                last, now);
    }

    private static MarketSession session(MarketVenue venue, MarketSession.State state,
                                         Observation last, Instant now) {
        return new MarketSession(venue, state, last.quotedAt(), last.tradeDate(), now);
    }

    /**
     * 거래소가 알려준 마지막 시세가 이미 지난 것인가.
     *
     * <p>국내는 마지막 시세 시각(HH:mm, KST)을 지금과 견준다. 해외는 체결일자를 그 거래소의
     * 오늘과 견준다 — 전 세션 값이면 장이 닫힌 것이다.
     */
    private static boolean quoteIsStale(MarketVenue venue, Observation last, Instant now) {
        if (venue == MarketVenue.KRX) {
            return krxQuoteIsStale(last.quotedAt(), now);
        }
        return usTradeDateIsStale(last.tradeDate(), now);
    }

    private static boolean krxQuoteIsStale(String quotedAt, Instant now) {
        if (quotedAt == null || !quotedAt.matches("\\d{1,2}:\\d{2}")) {
            return false;
        }
        java.time.ZonedDateTime kstNow = now.atZone(java.time.ZoneId.of("Asia/Seoul"));
        java.time.LocalTime quoted;
        try {
            quoted = java.time.LocalTime.parse(
                    quotedAt.length() == 4 ? "0" + quotedAt : quotedAt);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
        // 자정을 넘겨 비교하면 음수가 나온다 — 그 경우는 판단하지 않는다
        long minutesSince = java.time.Duration.between(quoted, kstNow.toLocalTime()).toMinutes();
        return minutesSince > QUOTE_STALE_MINUTES;
    }

    private static boolean usTradeDateIsStale(String tradeDate, Instant now) {
        if (tradeDate == null || !tradeDate.matches("\\d{8}")) {
            return false;
        }
        java.time.LocalDate quoted;
        try {
            quoted = java.time.LocalDate.parse(tradeDate,
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
        java.time.LocalDate exchangeToday =
                now.atZone(java.time.ZoneId.of("America/New_York")).toLocalDate();
        return quoted.isBefore(exchangeToday);
    }

    /** 지금 열려 있는 거래소가 하나라도 있는가. 화면이 "전 시장 마감"을 띄울지 정한다. */
    public Optional<MarketVenue> anyOpenVenue() {
        for (MarketVenue venue : MarketVenue.values()) {
            if (sessionOf(venue).open()) {
                return Optional.of(venue);
            }
        }
        return Optional.empty();
    }
}

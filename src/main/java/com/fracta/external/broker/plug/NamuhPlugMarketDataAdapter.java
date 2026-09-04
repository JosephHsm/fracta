package com.fracta.external.broker.plug;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fracta.common.config.BrokerProperties;
import com.fracta.common.money.Money;
import com.fracta.external.broker.Candle;
import com.fracta.external.broker.MarketDataPort;
import com.fracta.external.broker.MockMarketDataAdapter;
import com.fracta.external.broker.Quote;
import com.fracta.external.broker.Tick;

/**
 * namuh PLUG 시세 어댑터 (조회 전용 — 주문은 절대 보내지 않는다).
 *
 * <p>{@code plug} 프로파일에서만 활성화된다. 기본값은 {@link MockMarketDataAdapter}라
 * 증권사 장애나 자격증명 부재가 개발·CI를 막지 않는다.
 *
 * <p>응답 필드는 실제 캡처({@code scripts/plug/captured/})에서 확인한 것만 쓴다.
 * 같은 값이 엔드포인트에 따라 숫자로도 문자열로도 오므로 파싱을 관대하게 한다.
 */
@Component
@Profile("plug")
@Primary
public class NamuhPlugMarketDataAdapter implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(NamuhPlugMarketDataAdapter.class);

    private static final String EP_CURRENT_PRICE = "currentPrice";
    private static final String EP_OVERSEAS_CURRENT = "overseasCurrent";
    private static final String EP_PERIOD = "period";
    private static final String DEFAULT_MARKET = "KRX";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PlugApiClient client;
    private final BrokerProperties properties;
    private final MockMarketDataAdapter fallback;
    private final com.fracta.external.broker.MarketSessionTracker sessions;

    /** 장 마감 후 반환할 마지막 시세. 장중에는 짧은 TTL 캐시로도 쓴다. */
    private final Map<String, Quote> lastQuotes = new ConcurrentHashMap<>();

    /**
     * 장중 현재가 캐시 유효시간.
     *
     * <p>괴리율 산출이 <b>체결 1건마다</b> 현재가를 부르는데, 이 호출은 종목별 매칭 스레드
     * 안에서 동기로 일어난다. 캐시가 없으면 연속 체결이 그대로 증권사 왕복 횟수가 되고,
     * 그동안 그 파티션에 걸린 다른 종목의 매칭까지 멈춘다. 쿼터도 체결량에 비례해 태운다.
     *
     * <p>괴리율 임계치는 10%·20%다. 몇 초 된 시세로도 판정이 뒤집히지 않는다.
     */
    private final java.time.Duration quoteTtl;

    public NamuhPlugMarketDataAdapter(PlugApiClient client, BrokerProperties properties,
                                      MockMarketDataAdapter fallback,
                                      com.fracta.external.broker.MarketSessionTracker sessions,
                                      @org.springframework.beans.factory.annotation.Value(
                                              "${broker.quote-cache-ttl:3s}")
                                      java.time.Duration quoteTtl) {
        this.client = client;
        this.properties = properties;
        this.fallback = fallback;
        this.sessions = sessions;
        this.quoteTtl = quoteTtl;
    }

    /**
     * 현재가.
     *
     * <p>거래소에 따라 경로가 갈린다 — 국내는 {@code /krstock/quote/}, 해외는
     * {@code /gbstock/quote/}. 국내장이 닫힌 시간에도 미국장은 돌기 때문에, 거래소를 구분하지
     * 않으면 밤에는 화면이 통째로 멈춘다.
     *
     * <p><b>장 마감 여부를 시계로 판단하지 않는다.</b> 응답에 실려 오는 체결일자·누적거래량을
     * {@link com.fracta.external.broker.MarketSessionTracker} 에 넘겨 거래소가 스스로 알려주게
     * 한다. 그래야 공휴일·조기폐장·서머타임이 저절로 맞는다.
     */
    @Override
    public Quote getCurrentPrice(String ticker) {
        com.fracta.external.broker.MarketVenue venue =
                com.fracta.external.broker.MarketVenue.of(ticker);
        Quote cached = lastQuotes.get(ticker);
        if (isFresh(cached)) {
            return cached;
        }
        // 이 거래소가 닫혀 있으면 폴링하지 않고 마지막 시세를 쓴다. 상태를 아직 모르면
        // 한 번은 물어봐야 알 수 있으므로 조회한다.
        if (sessions.sessionOf(venue).state() == com.fracta.external.broker.MarketSession.State.CLOSED
                && cached != null) {
            return cached;
        }
        return venue.overseas() ? overseasQuote(ticker) : domesticQuote(ticker);
    }

    private Quote domesticQuote(String ticker) {
        if (!properties.supportedOnCurrentEnv(EP_CURRENT_PRICE)) {
            return fallbackQuote(ticker, "설정상 모의 도메인 미지원");
        }
        try {
            Map<String, Object> body = client.call(properties.endpoint(EP_CURRENT_PRICE),
                    Map.of("iem_cd", ticker, "market_cd", DEFAULT_MARKET));
            Map<String, Object> out = asMap(body.get("Output_0"));
            Quote quote = new Quote(ticker, Money.of(asLong(out.get("stck_prpr"))), Instant.now());
            lastQuotes.put(ticker, quote);
            sessions.observe(com.fracta.external.broker.MarketVenue.KRX, ticker,
                    asLong(out.get("acml_vol")),
                    asText(out.get("memb_bsop_hour")), null);
            return quote;
        } catch (BrokerApiException e) {
            if (isUnsupported(e)) {
                return fallbackQuote(ticker, "미지원 URI(IGW40401)");
            }
            throw e;
        }
    }

    /**
     * 해외 현재가. 응답 통화는 달러이고 소수점이 있다 — {@code Money} 는 원 단위 정수라
     * <b>센트 단위로 올려 담는다.</b> 조각 참조가는 분할비율로 다시 나누므로 단위가 일관되면 된다.
     */
    private Quote overseasQuote(String ticker) {
        if (!properties.supportedOnCurrentEnv(EP_OVERSEAS_CURRENT)) {
            return fallbackQuote(ticker, "설정상 해외 시세 미지원");
        }
        try {
            Map<String, Object> body = client.call(properties.endpoint(EP_OVERSEAS_CURRENT),
                    Map.of("iem_cd", ticker));
            Map<String, Object> out = asMap(body.get("Output_0"));
            long cents = asCents(out.get("trdprc"));
            if (cents <= 0) {
                // 해외 종목이 아니었거나(예: 합성 티커) 응답에 체결가가 없다.
                // 거래소는 종목코드 모양으로 추정하므로 빗나갈 수 있다 — 0원 시세를 내려보내면
                // 조각 참조가가 0이 되어 괴리율 계산이 통째로 무의미해진다.
                return fallbackQuote(ticker, "해외 시세에 체결가가 없다");
            }
            Quote quote = new Quote(ticker, Money.of(cents), Instant.now());
            lastQuotes.put(ticker, quote);
            sessions.observe(com.fracta.external.broker.MarketVenue.US, ticker,
                    asLong(out.get("acvol")),
                    asText(out.get("quote_time")), asText(out.get("trade_date")));
            return quote;
        } catch (BrokerApiException e) {
            if (isUnsupported(e)) {
                return fallbackQuote(ticker, "미지원 URI(IGW40401)");
            }
            throw e;
        }
    }

    @Override
    public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
        if (!properties.supportedOnCurrentEnv(EP_PERIOD)) {
            return fallbackCandles(ticker, from, to, "설정상 모의 도메인 미지원");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 구간이 뒤집혔다: %s ~ %s".formatted(from, to));
        }

        // 서버는 종료일 기준 N건을 돌려준다. 주말·휴일을 감안해 넉넉히 요청한 뒤 구간으로 자른다.
        long span = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        int requestCount = (int) Math.min(9999, Math.max(1, span));

        try {
            Map<String, Object> body = client.call(properties.endpoint(EP_PERIOD), Map.of(
                    "market_cd", DEFAULT_MARKET,
                    "iem_cd", ticker,
                    "mrkt_div_cls_code", "1",
                    "edate", to.format(YYYYMMDD),
                    "array_cnt", "%04d".formatted(Math.min(9999, requestCount)),
                    "gubun", "1",              // 1=일봉
                    "today_cls_code", "0",
                    "fake_tick", "1",          // 실봉만
                    "sur_flag", "0"));

            List<Candle> candles = new ArrayList<>();
            for (Object row : asList(body.get("Output_1"))) {
                Map<String, Object> c = asMap(row);
                LocalDate date = LocalDate.parse(String.valueOf(c.get("bsop_date")), YYYYMMDD);
                if (date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                candles.add(new Candle(date,
                        Money.of(asLong(c.get("stck_oprc"))),
                        Money.of(asLong(c.get("stck_hgpr"))),
                        Money.of(asLong(c.get("stck_lwpr"))),
                        Money.of(asLong(c.get("stck_prpr"))),   // 기간 조회의 종가 필드
                        asLong(c.get("vol"))));
            }
            candles.sort(java.util.Comparator.comparing(Candle::date));
            return candles;
        } catch (BrokerApiException e) {
            if (isUnsupported(e)) {
                return fallbackCandles(ticker, from, to, "미지원 URI(IGW40401)");
            }
            throw e;
        }
    }

    @Override
    public void subscribeRealtime(String ticker, Consumer<Tick> handler) {
        throw new UnsupportedOperationException(
                "실시간 구독은 PlugWebSocketClient 를 사용한다");
    }

    @Override
    public void unsubscribe(String ticker) {
        throw new UnsupportedOperationException(
                "실시간 구독은 PlugWebSocketClient 를 사용한다");
    }

    // ── 폴백 ────────────────────────────────────────────────

    /** 캐시된 시세가 아직 TTL 안인가. TTL이 0 이하면 캐시를 끈 것으로 본다. */
    private boolean isFresh(Quote cached) {
        if (cached == null || quoteTtl.isZero() || quoteTtl.isNegative()) {
            return false;
        }
        return cached.at().isAfter(Instant.now().minus(quoteTtl));
    }

    private boolean isUnsupported(BrokerApiException e) {
        Object code = e.details().get("rspCd");
        return code != null && PlugErrorCodes.isUnsupportedUri(String.valueOf(code));
    }

    private Quote fallbackQuote(String ticker, String reason) {
        log.warn("증권사 미지원으로 Mock 폴백 — ticker={} 사유={}", ticker, reason);
        return fallback.getCurrentPrice(ticker);
    }

    private List<Candle> fallbackCandles(String ticker, LocalDate from, LocalDate to, String reason) {
        log.warn("증권사 미지원으로 Mock 폴백 — ticker={} 사유={}", ticker, reason);
        return fallback.getDailyCandles(ticker, from, to);
    }

    // ── 파싱 (숫자/문자열 혼용 대응) ─────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** 달러 표기(예: "326.99")를 센트 정수로 바꾼다. 소수점을 버리지 않는다. */
    private static long asCents(Object value) {
        if (value == null) {
            return 0;
        }
        String text = String.valueOf(value).trim().replace(",", "");
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return new java.math.BigDecimal(text)
                    .movePointRight(2)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return 0;
        }
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String text = String.valueOf(value).trim().replace(",", "");
        if (text.isEmpty()) {
            return 0;
        }
        // 소수점이 붙어 오는 필드가 있어 절삭한다 (금액·수량은 정수여야 한다)
        int dot = text.indexOf('.');
        if (dot >= 0) {
            text = text.substring(0, dot);
        }
        return Long.parseLong(text);
    }
}

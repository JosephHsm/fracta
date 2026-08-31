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
    private static final String EP_PERIOD = "period";
    private static final String DEFAULT_MARKET = "KRX";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PlugApiClient client;
    private final BrokerProperties properties;
    private final MockMarketDataAdapter fallback;
    private final MarketHours marketHours;

    /** 장 마감 후 반환할 마지막 시세. */
    private final Map<String, Quote> lastQuotes = new ConcurrentHashMap<>();

    public NamuhPlugMarketDataAdapter(PlugApiClient client, BrokerProperties properties,
                                      MockMarketDataAdapter fallback, MarketHours marketHours) {
        this.client = client;
        this.properties = properties;
        this.fallback = fallback;
        this.marketHours = marketHours;
    }

    @Override
    public Quote getCurrentPrice(String ticker) {
        // 장 시간 외에는 폴링하지 않고 마지막 종가 캐시를 쓴다
        if (!marketHours.isOpen()) {
            Quote cached = lastQuotes.get(ticker);
            if (cached != null) {
                return cached;
            }
        }
        if (!properties.supportedOnCurrentEnv(EP_CURRENT_PRICE)) {
            return fallbackQuote(ticker, "설정상 모의 도메인 미지원");
        }

        try {
            Map<String, Object> body = client.call(properties.endpoint(EP_CURRENT_PRICE),
                    Map.of("iem_cd", ticker, "market_cd", DEFAULT_MARKET));
            Map<String, Object> out = asMap(body.get("Output_0"));
            Quote quote = new Quote(ticker, Money.of(asLong(out.get("stck_prpr"))), Instant.now());
            lastQuotes.put(ticker, quote);
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

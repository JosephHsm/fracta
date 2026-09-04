package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.config.BrokerProperties;
import com.fracta.common.money.Money;
import com.fracta.external.broker.MarketSessionTracker;
import com.fracta.external.broker.MarketVenue;
import com.fracta.external.broker.MockMarketDataAdapter;

/**
 * 시세 조회 캐시 정책.
 *
 * <p>두 가지가 증권사 호출을 아낀다 — <b>짧은 TTL</b>(연속 체결이 그대로 왕복 횟수가 되지 않게)과
 * <b>장 마감</b>(닫힌 시장을 계속 물어볼 이유가 없다).
 *
 * <p>마감 판정은 시간표가 아니라 {@link MarketSessionTracker} 가 거래소 응답으로 한다.
 * 예전에는 09:00~15:30 을 코드에 박아 두고 판단했는데, 공휴일·조기폐장을 몰랐고 해외 거래소는
 * 서머타임까지 있어 아예 맞출 수 없었다.
 */
class MarketClosedCacheTest {

    private static final Duration NO_CACHE = Duration.ZERO;

    private static BrokerProperties props() {
        return new BrokerProperties("mock", "https://api.nhplug.com:8443",
                "https://api.nhplug.com:8443", "03", false, "k", "s",
                new BrokerProperties.Token(1800, "p"),
                new BrokerProperties.RateLimit("sliding", 1, 1000),
                Map.of("currentPrice", "/krstock/quote/v1/currentPrice",
                        "period", "/krstock/quote/v1/period",
                        "overseasCurrent", "/gbstock/quote/v1/current"),
                java.util.Set.of());
    }

    /** 거래량이 늘지 않는 국내 응답 — 마지막 시세 시각은 15:30 이다. */
    private static PlugApiClient domesticApi(Object price, long volume) {
        PlugApiClient api = mock(PlugApiClient.class);
        when(api.call(anyString(), anyMap())).thenReturn(Map.of(
                "Output_0", Map.of("stck_prpr", price, "acml_vol", volume,
                        "memb_bsop_hour", "15:30"),
                "rsp_cd", "00000"));
        return api;
    }

    private static NamuhPlugMarketDataAdapter adapter(PlugApiClient api,
                                                      MarketSessionTracker sessions,
                                                      Duration quoteTtl) {
        return new NamuhPlugMarketDataAdapter(api, props(), new MockMarketDataAdapter(),
                sessions, quoteTtl);
    }

    /** 원하는 시각에 멈춰 있는 트래커. */
    private static MarketSessionTracker trackerAt(String isoInstant) {
        return new MarketSessionTracker(Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("TTL 안의 연속 조회는 증권사로 나가지 않는다")
    void shortTtlCollapsesBursts() {
        PlugApiClient api = domesticApi(258_500, 1_000);
        // KST 10:00 — 국내장 시간이고 마지막 시세 시각(15:30)이 아직 지나지 않았다
        var adapter = adapter(api, trackerAt("2026-09-04T01:00:00Z"), Duration.ofSeconds(30));

        for (int i = 0; i < 10; i++) {
            assertThat(adapter.getCurrentPrice("069500").price()).isEqualTo(Money.of(258_500));
        }
        verify(api, times(1)).call(anyString(), anyMap());
    }

    @Test
    @DisplayName("TTL 을 끄면 매번 조회한다")
    void noCacheAlwaysFetches() {
        PlugApiClient api = domesticApi(258_500, 1_000);
        var adapter = adapter(api, trackerAt("2026-09-04T01:00:00Z"), NO_CACHE);

        adapter.getCurrentPrice("069500");
        adapter.getCurrentPrice("069500");
        adapter.getCurrentPrice("069500");

        verify(api, times(3)).call(anyString(), anyMap());
    }

    @Test
    @DisplayName("장이 닫혔다고 판정되면 더 묻지 않고 마지막 시세를 쓴다")
    void closedMarketStopsPolling() {
        PlugApiClient api = domesticApi(258_500, 1_000);
        // 거래소가 준 마지막 시세 시각은 15:30 인데 지금은 KST 22:00 — 이미 지난 값이다
        var sessions = trackerAt("2026-09-04T13:00:00Z");
        var adapter = adapter(api, sessions, NO_CACHE);

        assertThat(adapter.getCurrentPrice("069500").price()).isEqualTo(Money.of(258_500));
        assertThat(sessions.sessionOf(MarketVenue.KRX).open()).isFalse();

        adapter.getCurrentPrice("069500");
        adapter.getCurrentPrice("069500");
        verify(api, times(1)).call(anyString(), anyMap());
    }

    @Test
    @DisplayName("해외 종목은 해외 시세 경로로 나간다 — 국내장이 닫혀도 조회된다")
    void overseasTickerUsesOverseasEndpoint() {
        PlugApiClient api = mock(PlugApiClient.class);
        when(api.call(anyString(), anyMap())).thenReturn(Map.of(
                "Output_0", Map.of("trdprc", "326.99", "acvol", 37_243_681L,
                        "trade_date", "20260904", "quote_time", "103000"),
                "rsp_cd", "00000"));
        // 국내장이 닫힌 KST 22:00 — 미국장은 이때 열린다
        var adapter = adapter(api, trackerAt("2026-09-04T13:00:00Z"), NO_CACHE);

        var quote = adapter.getCurrentPrice("AAPL");

        // 달러 소수점은 센트 정수로 담는다 ($326.99 → 32,699)
        assertThat(quote.price()).isEqualTo(Money.of(32_699));
        verify(api).call(org.mockito.ArgumentMatchers.eq("/gbstock/quote/v1/current"), anyMap());
    }
}

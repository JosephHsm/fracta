package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.config.BrokerProperties;
import com.fracta.common.money.Money;
import com.fracta.external.broker.MockMarketDataAdapter;

/** 장 시간 외 폴링 중단 + 마지막 종가 캐시 반환 (phase-05 §3.4). */
class MarketClosedCacheTest {

    private static BrokerProperties props() {
        return new BrokerProperties("mock", "https://moapi.nhplug.com:8443",
                "https://api.nhplug.com:8443", "03", false, "k", "s",
                new BrokerProperties.Token(1800, "p"),
                new BrokerProperties.RateLimit("sliding", 1, 1000),
                Map.of("currentPrice", "/krstock/quote/v1/currentPrice",
                        "period", "/krstock/quote/v1/period"),
                java.util.Set.of());
    }

    private static MarketHours at(String isoDateTime) {
        return new MarketHours(Clock.fixed(
                LocalDateTime.parse(isoDateTime).atZone(MarketHours.KST).toInstant(), MarketHours.KST));
    }

    @Test
    @DisplayName("개장 중 조회한 값을 장 마감 후에는 재호출 없이 캐시로 돌려준다")
    void servesCachedQuoteAfterClose() {
        PlugApiClient api = mock(PlugApiClient.class);
        when(api.call(anyString(), anyMap())).thenReturn(Map.of(
                "Output_0", Map.of("stck_prpr", 258_500),
                "rsp_cd", "00000"));

        var openHours = at("2026-08-31T10:00:00");
        var adapter = new NamuhPlugMarketDataAdapter(api, props(), new MockMarketDataAdapter(), openHours);

        var duringSession = adapter.getCurrentPrice("005930");
        assertThat(duringSession.price()).isEqualTo(Money.of(258_500));
        verify(api, times(1)).call(anyString(), anyMap());

        // 같은 어댑터를 장 마감 시각으로 옮긴다
        var closedAdapter = new NamuhPlugMarketDataAdapter(api, props(), new MockMarketDataAdapter(),
                at("2026-08-31T20:00:00"));
        // 캐시를 공유하지 않는 새 인스턴스라 한 번은 호출된다 — 캐시를 채운 뒤 다시 확인한다
        closedAdapter.getCurrentPrice("005930");
        int callsAfterWarmup = mockingDetails(api);

        var afterClose = closedAdapter.getCurrentPrice("005930");
        assertThat(afterClose.price()).isEqualTo(Money.of(258_500));
        // 캐시가 찼으므로 추가 호출이 없어야 한다
        assertThat(mockingDetails(api)).isEqualTo(callsAfterWarmup);
    }

    @Test
    @DisplayName("장 시간 외 + 캐시 없음 → 1회만 조회해 캐시를 채운다")
    void fetchesOnceWhenCacheEmpty() {
        PlugApiClient api = mock(PlugApiClient.class);
        when(api.call(anyString(), anyMap())).thenReturn(Map.of(
                "Output_0", Map.of("stck_prpr", "100000"),
                "rsp_cd", "00000"));

        var adapter = new NamuhPlugMarketDataAdapter(api, props(), new MockMarketDataAdapter(),
                at("2026-08-30T11:00:00"));   // 일요일 — 휴장

        assertThat(adapter.getCurrentPrice("005930").price()).isEqualTo(Money.of(100_000));
        verify(api, times(1)).call(anyString(), anyMap());

        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("005930");
        // 이후 호출은 전부 캐시 — 휴장 중 폴링이 나가지 않는다
        verify(api, times(1)).call(anyString(), anyMap());
    }

    @Test
    @DisplayName("개장 중에는 매번 최신 시세를 조회한다 (캐시로 대체하지 않는다)")
    void alwaysFetchesWhileOpen() {
        PlugApiClient api = mock(PlugApiClient.class);
        when(api.call(anyString(), anyMap())).thenReturn(Map.of(
                "Output_0", Map.of("stck_prpr", 258_500),
                "rsp_cd", "00000"));

        var adapter = new NamuhPlugMarketDataAdapter(api, props(), new MockMarketDataAdapter(),
                at("2026-08-31T10:00:00"));

        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("005930");
        verify(api, times(3)).call(anyString(), anyMap());
        verify(api, never()).call(anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    private static int mockingDetails(PlugApiClient api) {
        return org.mockito.Mockito.mockingDetails(api).getInvocations().size();
    }
}

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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.config.BrokerProperties;
import com.fracta.common.money.Money;
import com.fracta.external.broker.MockMarketDataAdapter;

/** 장 시간 외 폴링 중단 + 마지막 종가 캐시 반환 (phase-05 §3.4) + 장중 단기 캐시. */
class MarketClosedCacheTest {

    /** 장중 캐시를 끈 설정 — 매 호출이 증권사로 나간다. */
    private static final Duration NO_CACHE = Duration.ZERO;

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

    private static NamuhPlugMarketDataAdapter adapter(PlugApiClient api, MarketHours hours,
                                                      Duration quoteTtl) {
        return new NamuhPlugMarketDataAdapter(api, props(), new MockMarketDataAdapter(), hours,
                quoteTtl);
    }

    private static PlugApiClient apiReturning(Object price) {
        PlugApiClient api = mock(PlugApiClient.class);
        when(api.call(anyString(), anyMap())).thenReturn(Map.of(
                "Output_0", Map.of("stck_prpr", price),
                "rsp_cd", "00000"));
        return api;
    }

    @Test
    @DisplayName("개장 중 조회한 값을 장 마감 후에는 재호출 없이 캐시로 돌려준다")
    void servesCachedQuoteAfterClose() {
        PlugApiClient api = apiReturning(258_500);

        var adapter = adapter(api, at("2026-08-31T10:00:00"), NO_CACHE);

        var duringSession = adapter.getCurrentPrice("005930");
        assertThat(duringSession.price()).isEqualTo(Money.of(258_500));
        verify(api, times(1)).call(anyString(), anyMap());

        // 같은 어댑터를 장 마감 시각으로 옮긴다
        var closedAdapter = adapter(api, at("2026-08-31T20:00:00"), NO_CACHE);
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
        PlugApiClient api = apiReturning("100000");

        var adapter = adapter(api, at("2026-08-30T11:00:00"), NO_CACHE);   // 일요일 — 휴장

        assertThat(adapter.getCurrentPrice("005930").price()).isEqualTo(Money.of(100_000));
        verify(api, times(1)).call(anyString(), anyMap());

        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("005930");
        // 이후 호출은 전부 캐시 — 휴장 중 폴링이 나가지 않는다
        verify(api, times(1)).call(anyString(), anyMap());
    }

    @Test
    @DisplayName("장중 캐시를 끄면(TTL 0) 매번 최신 시세를 조회한다")
    void alwaysFetchesWhileOpenWithoutCache() {
        PlugApiClient api = apiReturning(258_500);

        var adapter = adapter(api, at("2026-08-31T10:00:00"), NO_CACHE);

        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("005930");
        verify(api, times(3)).call(anyString(), anyMap());
        verify(api, never()).call(anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("장중 단기 캐시 — TTL 안의 연속 조회는 증권사로 나가지 않는다")
    void shortTtlCollapsesBurstsWhileOpen() {
        PlugApiClient api = apiReturning(258_500);

        // 괴리율은 체결 1건마다 현재가를 부른다. 캐시가 없으면 연속 체결이 그대로
        // 증권사 왕복 횟수가 되고, 그동안 매칭 스레드가 멈춘다.
        var adapter = adapter(api, at("2026-08-31T10:00:00"), Duration.ofSeconds(30));

        for (int i = 0; i < 10; i++) {
            assertThat(adapter.getCurrentPrice("005930").price()).isEqualTo(Money.of(258_500));
        }
        verify(api, times(1)).call(anyString(), anyMap());
    }

    @Test
    @DisplayName("장중 단기 캐시는 종목별로 따로 잡힌다")
    void shortTtlIsPerTicker() {
        PlugApiClient api = apiReturning(258_500);

        var adapter = adapter(api, at("2026-08-31T10:00:00"), Duration.ofSeconds(30));

        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("000660");
        adapter.getCurrentPrice("005930");
        adapter.getCurrentPrice("000660");
        // 종목마다 한 번씩만 — 한 종목 캐시가 다른 종목을 가리면 안 된다
        verify(api, times(2)).call(anyString(), anyMap());
    }

    private static int mockingDetails(PlugApiClient api) {
        return org.mockito.Mockito.mockingDetails(api).getInvocations().size();
    }
}

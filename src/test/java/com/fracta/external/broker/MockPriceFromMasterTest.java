package com.fracta.external.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.external.broker.instrument.InstrumentSearchService;
import com.fracta.support.IntegrationTestBase;

/**
 * Mock 시세의 기준가는 종목마스터의 전일종가를 따른다.
 *
 * <p>예전에는 티커 숫자를 그대로 기준가로 썼다. {@code 365550}(ESR켄달스퀘어리츠)이
 * 365,550원이 돼 실제 3,000원대와 100배 넘게 벌어졌고, mock 으로 만든 시드를 열면
 * 괴리율이 -98%로 나와 기동하자마자 자동 거래중단이 걸렸다. 시드와 런타임이 같은
 * 기준가를 보게 만드는 것이 요점이다.
 */
class MockPriceFromMasterTest extends IntegrationTestBase {

    private static final String CODE = "099999";
    private static final long PREV_CLOSE = 37_400;

    @Autowired
    MockMarketDataAdapter adapter;

    @Autowired
    InstrumentSearchService instruments;

    @Autowired
    JdbcTemplate jdbc;

    private void seedMaster(long prevClose) {
        jdbc.update("""
                INSERT INTO instrument_master (code, market, kor_name, asset_kind, prev_close)
                VALUES (?, 'KRX', '테스트ETF', 'ETF', ?)
                ON CONFLICT (code) DO UPDATE SET prev_close = EXCLUDED.prev_close
                """, CODE, prevClose);
    }

    @Test
    @DisplayName("마스터에 있는 종목은 전일종가 근처에서 출발한다 — 티커 숫자가 아니라")
    void basePriceComesFromMaster() {
        seedMaster(PREV_CLOSE);

        long price = adapter.getCurrentPrice(CODE).price().amount();

        // 호출마다 ±1% 이내로 움직인다
        assertThat(price)
                .as("티커 숫자(%s)가 아니라 전일종가(%d) 근처여야 한다", CODE, PREV_CLOSE)
                .isBetween(Math.round(PREV_CLOSE * 0.98), Math.round(PREV_CLOSE * 1.02));
    }

    @Test
    @DisplayName("마스터에 없는 티커는 예전 규칙 그대로 — 테스트가 기준가를 티커로 제어한다")
    void unknownTickerFallsBackToDigits() {
        long price = new MockMarketDataAdapter().getCurrentPrice("MOCK-4200").price().amount();

        assertThat(price).isBetween(4_158L, 4_242L);
    }

    @Test
    @DisplayName("시드가 보는 조각 참조가와 Mock 현재가가 같은 기준에서 나온다")
    void seedAndRuntimeShareTheSameBasis() {
        seedMaster(PREV_CLOSE);
        long splitRatio = 100;

        // 시드 스크립트가 부르는 경로
        var detail = instruments.detail(CODE, splitRatio).orElseThrow();
        // 괴리율이 런타임에 부르는 경로
        long runtimeUnderlying = adapter.getCurrentPrice(CODE).price().amount();

        assertThat(detail.underlyingPrice())
                .as("두 경로가 같은 시세원을 봐야 한다")
                .isBetween(Math.round(runtimeUnderlying * 0.95), Math.round(runtimeUnderlying * 1.05));

        // 시드가 이 참조가로 발행가를 정하면 괴리율은 0 근처에서 시작한다
        long unitPrice = detail.referencePrice();
        long reference = Math.round((double) runtimeUnderlying / splitRatio);
        double premiumPercent = (unitPrice - reference) * 100.0 / reference;
        assertThat(Math.abs(premiumPercent))
                .as("발행가와 참조가가 100배 넘게 벌어지면 기동하자마자 자동 중단이 걸린다")
                .isLessThan(10);
    }
}

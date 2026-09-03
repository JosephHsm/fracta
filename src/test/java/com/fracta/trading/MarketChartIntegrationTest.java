package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.TradingTestSupport;

/**
 * 기초자산 시세 차트 (FSD §11 종목 상세 필수 요소).
 *
 * <p>핵심은 <b>서버가 조각 참조가로 환산해서 준다</b>는 것이다. 원자산 가격을 그대로 주면
 * 화면이 분할비율로 나눠야 하고, 그건 프론트 금액 재계산 금지에 걸린다.
 */
class MarketChartIntegrationTest extends IntegrationTestBase {

    private static final AtomicLong TICKER_SEQ = new AtomicLong();

    @Autowired
    TradingTestSupport support;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestRestTemplate rest;

    /** Mock 어댑터는 티커의 첫 숫자를 기준가로 쓴다. 티커를 갈라 테스트 간 시세 드리프트를 막는다. */
    private String freshTicker() {
        return "MOCK-100000-chart" + TICKER_SEQ.incrementAndGet();
    }

    private JsonNode get(String url, String token) throws Exception {
        ResponseEntity<String> response = rest.exchange(
                url, HttpMethod.GET, new HttpEntity<>(auth.bearer(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    @Test
    @DisplayName("일봉이 조각 참조가로 환산되어 내려온다 — 화면이 다시 계산하지 않는다")
    void candlesAreConvertedToReferencePrice() throws Exception {
        // 기준가 100,000 · 분할비율 100 → 조각 참조가 ≈ 1,000
        var market = support.listedMarket(freshTicker(), 100);
        String token = auth.signupAndLogin("chart-viewer").token();

        JsonNode data = get("/api/v1/tokens/" + market.tokenSymbol() + "/candles?days=30", token);

        assertThat(data.path("splitRatio").asLong()).isEqualTo(100);
        JsonNode candles = data.path("candles");
        assertThat(candles).isNotEmpty();

        for (JsonNode candle : candles) {
            // 원자산 100,000원대가 그대로 오면 이 단언이 깨진다 — 환산 누락을 잡는 지점이다
            assertThat(candle.path("close").asLong())
                    .as("조각 참조가여야 한다 (원자산 가격이 아니라)")
                    .isBetween(500L, 2_000L);
            assertThat(candle.path("high").asLong())
                    .isGreaterThanOrEqualTo(candle.path("low").asLong());
            assertThat(candle.path("date").asText()).hasSize(10).contains("-");
        }
    }

    @Test
    @DisplayName("증권사 티커가 없는 종목은 빈 목록 — 오류가 아니다")
    void tokenWithoutTickerReturnsEmptyList() throws Exception {
        var market = support.listedMarket(null, 100);
        String token = auth.signupAndLogin("chart-no-ticker").token();

        JsonNode data = get("/api/v1/tokens/" + market.tokenSymbol() + "/candles", token);

        assertThat(data.path("brokerTicker").isNull()).isTrue();
        assertThat(data.path("candles")).isEmpty();
    }

    @Test
    @DisplayName("조회 기간이 범위를 벗어나면 400 VALID_INVALID_INPUT")
    void rejectsOutOfRangeDays() throws Exception {
        var market = support.listedMarket(freshTicker(), 100);
        String token = auth.signupAndLogin("chart-bad-range").token();

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/tokens/" + market.tokenSymbol() + "/candles?days=9999",
                HttpMethod.GET, new HttpEntity<>(auth.bearer(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).path("error").path("code").asText())
                .isEqualTo("VALID_INVALID_INPUT");
    }
}

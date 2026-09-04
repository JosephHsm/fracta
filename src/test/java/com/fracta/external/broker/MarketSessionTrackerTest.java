package com.fracta.external.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장 상태를 <b>거래소가 준 값</b>으로 판정한다 — 시간표가 아니다.
 *
 * <p>09:00~15:30 을 코드에 박아 두면 공휴일·임시휴장·조기폐장을 전혀 모르고, 해외 거래소는
 * 서머타임까지 있어 맞출 수 없다. 그래서 누적거래량이 느는지와 거래소가 준 마지막 시각·
 * 체결일자가 지났는지를 본다.
 */
class MarketSessionTrackerTest {

    private static MarketSessionTracker at(String isoInstant) {
        return new MarketSessionTracker(Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("거래량이 늘면 장중이다 — 시각과 무관하게")
    void volumeGrowthMeansOpen() {
        // KST 03:00 — 시간표로만 보면 마감이지만, 거래량이 늘면 그게 사실이다
        var tracker = at("2026-09-03T18:00:00Z");

        tracker.observe(MarketVenue.KRX, "069500", 1_000, "03:00", null);
        tracker.observe(MarketVenue.KRX, "069500", 1_500, "03:01", null);

        assertThat(tracker.sessionOf(MarketVenue.KRX).state())
                .isEqualTo(MarketSession.State.OPEN);
    }

    @Test
    @DisplayName("거래소가 준 마지막 시세 시각이 한참 지났으면 첫 관측에서 바로 마감이다")
    void staleQuoteTimeMeansClosedImmediately() {
        // KST 22:00 인데 거래소는 15:30 이 마지막이라고 한다
        var tracker = at("2026-09-04T13:00:00Z");

        tracker.observe(MarketVenue.KRX, "069500", 1_000, "15:30", null);

        var session = tracker.sessionOf(MarketVenue.KRX);
        assertThat(session.state()).isEqualTo(MarketSession.State.CLOSED);
        assertThat(session.describe()).isEqualTo("국내장 마감 · 15:30 기준");
    }

    @Test
    @DisplayName("거래소 시각이 방금 것이면 마감으로 단정하지 않는다")
    void freshQuoteTimeIsNotClosed() {
        // KST 10:05, 거래소 마지막 시세 10:00 — 아직 지난 값이 아니다
        var tracker = at("2026-09-04T01:05:00Z");

        tracker.observe(MarketVenue.KRX, "069500", 1_000, "10:00", null);

        assertThat(tracker.sessionOf(MarketVenue.KRX).state())
                .isEqualTo(MarketSession.State.UNKNOWN);
    }

    @Test
    @DisplayName("해외는 체결일자가 전 세션이면 마감이다")
    void previousTradeDateMeansClosed() {
        // 미 동부 2026-09-04 03:35 — 체결일자가 9월 3일이면 지난 세션 값이다
        var tracker = at("2026-09-04T07:35:00Z");

        tracker.observe(MarketVenue.US, "AAPL", 37_243_681, "273357", "20260903");

        var session = tracker.sessionOf(MarketVenue.US);
        assertThat(session.state()).isEqualTo(MarketSession.State.CLOSED);
        assertThat(session.describe())
                .as("거래소 시계(273357)는 사람이 읽을 수 없다 — 체결일자를 보여준다")
                .isEqualTo("미국장 마감 · 9월 3일 기준");
    }

    @Test
    @DisplayName("해외 체결일자가 오늘이면 마감으로 단정하지 않는다")
    void todayTradeDateIsNotClosed() {
        // 미 동부 2026-09-04 10:00 (KST 23:00) — 장중이다
        var tracker = at("2026-09-04T14:00:00Z");

        tracker.observe(MarketVenue.US, "AAPL", 1_000, "100000", "20260904");

        assertThat(tracker.sessionOf(MarketVenue.US).state())
                .isEqualTo(MarketSession.State.UNKNOWN);
    }

    @Test
    @DisplayName("한 종목이 한산해도 다른 종목이 돌면 그 시장은 열려 있다")
    void quietTickerDoesNotCloseTheVenue() {
        var tracker = at("2026-09-04T01:00:00Z");

        tracker.observe(MarketVenue.KRX, "069500", 1_000, "10:00", null);
        tracker.observe(MarketVenue.KRX, "069500", 1_000, "10:01", null);   // 한산
        tracker.observe(MarketVenue.KRX, "133690", 5_000, "10:00", null);
        tracker.observe(MarketVenue.KRX, "133690", 5_500, "10:01", null);   // 활발

        assertThat(tracker.sessionOf(MarketVenue.KRX).state())
                .isEqualTo(MarketSession.State.OPEN);
    }

    @Test
    @DisplayName("국내가 닫혀도 미국이 열려 있으면 열린 시장이 있다고 본다")
    void overseasKeepsTheLightsOn() {
        var tracker = at("2026-09-04T13:00:00Z");   // KST 22:00

        tracker.observe(MarketVenue.KRX, "069500", 1_000, "15:30", null);
        tracker.observe(MarketVenue.US, "AAPL", 1_000, "093000", "20260904");
        tracker.observe(MarketVenue.US, "AAPL", 1_200, "093001", "20260904");

        assertThat(tracker.sessionOf(MarketVenue.KRX).open()).isFalse();
        assertThat(tracker.sessionOf(MarketVenue.US).open()).isTrue();
        assertThat(tracker.anyOpenVenue()).contains(MarketVenue.US);
    }

    @Test
    @DisplayName("관측이 없으면 모른다고 한다 — 마감이라고 단정하지 않는다")
    void noObservationMeansUnknown() {
        var tracker = at("2026-09-04T01:00:00Z");

        assertThat(tracker.sessionOf(MarketVenue.KRX).state())
                .isEqualTo(MarketSession.State.UNKNOWN);
        assertThat(tracker.anyOpenVenue()).isEmpty();
    }

    @Test
    @DisplayName("종목코드로 거래소를 가른다 — 숫자면 국내, 영문이면 해외")
    void venueFromCode() {
        assertThat(MarketVenue.of("069500")).isEqualTo(MarketVenue.KRX);
        assertThat(MarketVenue.of("AAPL")).isEqualTo(MarketVenue.US);
        assertThat(MarketVenue.of(null)).isEqualTo(MarketVenue.KRX);
        assertThat(MarketVenue.of("  ")).isEqualTo(MarketVenue.KRX);
    }
}

package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketHoursTest {

    private static MarketHours at(String isoLocalDateTime) {
        Instant fixed = LocalDateTime.parse(isoLocalDateTime).atZone(MarketHours.KST).toInstant();
        return new MarketHours(Clock.fixed(fixed, MarketHours.KST));
    }

    @Test
    @DisplayName("평일 09:00~15:30 은 개장, 경계 포함")
    void weekdayOpenBoundaries() {
        assertThat(at("2026-08-31T09:00:00").isOpen()).isTrue();   // 월요일 개장 정각
        assertThat(at("2026-08-31T12:00:00").isOpen()).isTrue();
        assertThat(at("2026-08-31T15:30:00").isOpen()).isTrue();   // 마감 정각 포함
    }

    @Test
    @DisplayName("장 시작 전·마감 후는 휴장")
    void outsideHours() {
        assertThat(at("2026-08-31T08:59:59").isOpen()).isFalse();
        assertThat(at("2026-08-31T15:30:01").isOpen()).isFalse();
        assertThat(at("2026-08-31T23:59:59").isOpen()).isFalse();
    }

    @Test
    @DisplayName("주말은 시간과 무관하게 휴장")
    void weekend() {
        assertThat(at("2026-08-29T11:00:00").isOpen()).isFalse();  // 토요일
        assertThat(at("2026-08-30T11:00:00").isOpen()).isFalse();  // 일요일
    }
}

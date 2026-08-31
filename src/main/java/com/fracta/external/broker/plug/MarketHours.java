package com.fracta.external.broker.plug;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

/** 국내 정규장 시간 판정 (09:00~15:30, 평일). 장 외에는 폴링·구독을 중단한다. */
@Component
public class MarketHours {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalTime OPEN = LocalTime.of(9, 0);
    static final LocalTime CLOSE = LocalTime.of(15, 30);

    private final Clock clock;

    public MarketHours() {
        this(Clock.system(KST));
    }

    /** 테스트에서 시각을 고정하기 위한 생성자. */
    public MarketHours(Clock clock) {
        this.clock = clock;
    }

    public boolean isOpen() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(OPEN) && !time.isAfter(CLOSE);
    }
}

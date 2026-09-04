package com.fracta.external.broker.plug;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

/** 국내 정규장 시간 판정 (09:00~15:30, 평일). 장 외에는 폴링·구독을 중단한다. */
/**
 * <b>WebSocket 연결 스케줄 전용이다. 시세 개장 판정에는 쓰지 않는다.</b>
 *
 * <p>여기 박힌 09:00~15:30 은 공휴일·임시휴장·조기폐장을 모르고, 해외 거래소는 서머타임까지
 * 있어 시간표로는 맞출 수 없다. 장 상태는 {@code MarketSessionTracker} 가 거래소 응답
 * (체결일자·누적거래량)으로 판정한다.
 *
 * <p>이 클래스가 남아 있는 이유는 WebSocket 을 언제 연결해 볼지 정하는 데는 대략의 시간표로
 * 충분하기 때문이다 — 틀려도 연결 시도가 한 번 헛돌 뿐이다.
 */
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

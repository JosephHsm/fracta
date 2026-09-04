package com.fracta.trading.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 자체 오더북의 거래 시간 (TR-01/02).
 *
 * <p><b>증권사 장 시간과 별개다.</b> 조각 거래가 원자산 시장과 같은 시간에만 열려야 할 이유는
 * 없다 — 다만 지금은 괴리율 판정의 기준 시세가 증권사에서 오므로, 장이 닫힌 동안에는
 * 마지막 종가 캐시를 보게 된다. 그 상태로 오더북을 24시간 열어 두면 새벽 체결 하나가
 * 전일 종가 대비 임계를 넘겨 자동 거래중단을 부를 수 있다.
 *
 * <p>기본값은 평일 09:00~15:30 KST다. {@code trading.hours.enabled=false} 로 끄면
 * 예전처럼 24시간 접수한다 — 테스트와 데모는 그 경로를 쓴다.
 */
@Component
public class TradingHours {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final boolean enabled;
    private final LocalTime open;
    private final LocalTime close;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TradingHours(@Value("${trading.hours.enabled:false}") boolean enabled,
                        @Value("${trading.hours.open:09:00}") String open,
                        @Value("${trading.hours.close:15:30}") String close) {
        this(enabled, LocalTime.parse(open), LocalTime.parse(close), Clock.system(KST));
    }

    /**
     * 시각을 고정해 만드는 생성자. 테스트가 요일·시간대별 동작을 확인할 때 쓴다
     * ({@code MarketHours}와 같은 방식).
     */
    public TradingHours(boolean enabled, LocalTime open, LocalTime close, Clock clock) {
        if (!open.isBefore(close)) {
            throw new IllegalArgumentException("개장 시각이 마감보다 빨라야 한다: %s ~ %s"
                    .formatted(open, close));
        }
        this.enabled = enabled;
        this.open = open;
        this.close = close;
        this.clock = clock;
    }

    /** 지금 주문을 받을 수 있는가. 게이트를 끈 상태면 항상 참이다. */
    public boolean isOpen() {
        if (!enabled) {
            return true;
        }
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(open) && !time.isAfter(close);
    }

    /** 화면·에러 메시지에 쓸 운영 시간 표기. */
    public String describe() {
        return enabled ? "평일 %s~%s (KST)".formatted(open, close) : "상시";
    }
}

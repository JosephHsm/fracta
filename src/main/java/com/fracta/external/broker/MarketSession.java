package com.fracta.external.broker;

import java.time.Instant;

/**
 * 한 거래소의 장 상태 — <b>시간표가 아니라 거래소가 준 값으로 판정한다.</b>
 *
 * <p>예전에는 평일 09:00~15:30 을 코드에 박아 두고 그 밖이면 마감으로 봤다. 공휴일·임시휴장·
 * 조기폐장을 전혀 모르고, 해외 거래소는 서머타임까지 있어 시간표로는 맞출 수 없다.
 *
 * <p>지금은 시세 응답에 실려 오는 <b>체결일자와 누적거래량</b>을 본다. 장이 열려 있으면 거래량이
 * 늘고, 닫히면 그 자리에 멈춘다. 거래소가 스스로 알려주는 사실이라 휴장일도 저절로 맞는다.
 *
 * <p>주의할 것 하나 — 응답의 {@code quote_time} 은 거래소 <b>시계</b>라 마감에도 계속 흐른다.
 * 그걸 개장 신호로 쓰면 정확히 "실시간인 척"이 된다.
 *
 * @param venue        거래소
 * @param state        판정 결과
 * @param lastQuotedAt 거래소가 알려준 마지막 시세 시각 표기(예: "15:34"). 없으면 null
 * @param tradeDate    거래소가 알려준 체결일자(yyyyMMdd). 없으면 null
 * @param observedAt   이 판정을 만든 시각
 */
public record MarketSession(MarketVenue venue, State state, String lastQuotedAt,
                            String tradeDate, Instant observedAt) {

    public enum State {
        /** 거래량이 움직이고 있다. */
        OPEN,
        /** 응답은 오는데 거래량이 멈춰 있다 — 마감이거나 휴장이다. */
        CLOSED,
        /** 아직 판단할 만큼 관측하지 못했다. 두 번째 조회부터 갈린다. */
        UNKNOWN
    }

    public static MarketSession unknown(MarketVenue venue) {
        return new MarketSession(venue, State.UNKNOWN, null, null, Instant.now());
    }

    public boolean open() {
        return state == State.OPEN;
    }

    /** 화면에 그대로 쓸 수 있는 문구. */
    public String describe() {
        String name = venue == MarketVenue.KRX ? "국내장" : "미국장";
        return switch (state) {
            case OPEN -> name + " 장중";
            case CLOSED -> {
                String basis = venue == MarketVenue.KRX ? lastQuotedAt : formatTradeDate(tradeDate);
                yield basis == null ? name + " 마감"
                        : "%s 마감 · %s 기준".formatted(name, basis);
            }
            case UNKNOWN -> name + " 상태 확인 중";
        };
    }

    /**
     * 체결일자 표기(yyyyMMdd → M월 d일).
     *
     * <p>해외 응답의 {@code quote_time} 은 쓰지 않는다 — 거래소 <b>시계</b>라 마감에도 흐르고,
     * 24시를 넘기면 {@code 274243} 같은 값이 나와 사람이 읽을 수 없다. 마감 상태에서 의미 있는
     * 기준은 "언제 세션의 값이냐"이므로 체결일자를 보여준다.
     */
    private static String formatTradeDate(String yyyyMMdd) {
        if (yyyyMMdd == null || !yyyyMMdd.matches("\\d{8}")) {
            return null;
        }
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(yyyyMMdd,
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return "%d월 %d일".formatted(d.getMonthValue(), d.getDayOfMonth());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}

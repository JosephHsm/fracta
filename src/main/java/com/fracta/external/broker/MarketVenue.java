package com.fracta.external.broker;

/**
 * 시세를 받아오는 거래소 구분.
 *
 * <p>국내장이 닫힌 시간에도 화면이 살아 있으려면 어느 거래소의 종목인지 알아야 한다.
 * 증권사 API도 국내({@code /krstock/quote/})와 해외({@code /gbstock/quote/})가 경로부터 다르다.
 *
 * <p>종목코드로 판별한다 — 국내는 6자리 숫자, 해외는 영문 티커다. 발행인이 따로 고르게 하면
 * 잘못 고를 수 있고, 그 경우 엉뚱한 거래소에 조회를 날리게 된다.
 */
public enum MarketVenue {

    /** 한국거래소. 코드가 6자리 숫자다 (예: 069500). */
    KRX,

    /** 미국 시장(나스닥·NYSE). 코드가 영문 티커다 (예: AAPL). */
    US;

    /** 종목코드로 거래소를 판별한다. 비어 있으면 국내로 본다(기존 데이터 호환). */
    public static MarketVenue of(String code) {
        if (code == null || code.isBlank()) {
            return KRX;
        }
        String trimmed = code.trim();
        return trimmed.chars().allMatch(Character::isDigit) ? KRX : US;
    }

    public boolean overseas() {
        return this == US;
    }
}

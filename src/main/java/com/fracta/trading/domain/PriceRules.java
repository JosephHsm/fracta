package com.fracta.trading.domain;

/**
 * 주문 가격 규칙 — 순수 함수. 호가단위와 가격제한폭 (TR-01/02).
 *
 * <p><b>왜 필요한가.</b> 이 규칙이 없으면 지정가를 1원 단위로 아무 값이나 걸 수 있고, 시장가는
 * 호가창을 끝까지 쓸어담는다. 그러면 <b>괴리율 자동 거래중단(TR-08)이 사실상 유일한 가격
 * 안전장치</b>가 되는데, 그건 사후 조치다. 사전 방어가 없어서 사후 조치로 막는 구조인데
 * 사후 조치(전 주문 취소)의 비용이 훨씬 크다.
 *
 * <p>기준가는 {@code ReferencePriceService}가 정한다 — 마지막 체결가, 없으면 발행 단가.
 * 화면에 보이는 평가 기준가와 주문이 거부되는 기준이 같아야 한다.
 */
public final class PriceRules {

    /**
     * 호가단위 — 가격대가 높을수록 단위가 커진다. 국내 시장 관행을 조각 단가 규모에 맞춰
     * 축소한 것이다. 조각은 보통 수백~수만 원이라 상위 구간은 두지 않는다.
     *
     * <p>단위가 없으면 1원 차이로 호가를 가로채는 주문이 무한히 쌓여 호가창이 의미를 잃는다.
     */
    private static final long[][] TICK_TABLE = {
            //  이 가격 미만 , 호가단위
            {      1_000,      1 },
            {     10_000,     10 },
            {    100_000,     50 },
            {  1_000_000,    100 },
    };

    /** 최상위 구간(100만원 이상)의 호가단위. */
    private static final long TOP_TICK = 1_000;

    private PriceRules() {
    }

    /** 이 가격에 적용되는 호가단위. */
    public static long tickSizeOf(long price) {
        if (price <= 0) {
            throw new IllegalArgumentException("가격은 1 이상이어야 한다: " + price);
        }
        for (long[] row : TICK_TABLE) {
            if (price < row[0]) {
                return row[1];
            }
        }
        return TOP_TICK;
    }

    /** 호가단위에 맞는 가격인가. */
    public static boolean isOnTick(long price) {
        return price > 0 && price % tickSizeOf(price) == 0;
    }

    /**
     * 호가단위에 맞춰 <b>내림</b>한다. 사용자가 입력한 값을 조용히 올리면 의도보다 비싸게
     * 사거나 싸게 팔게 된다 — 자동 보정은 항상 사용자에게 불리하지 않은 쪽으로 한다.
     */
    public static long floorToTick(long price) {
        if (price <= 0) {
            return 0;
        }
        long tick = tickSizeOf(price);
        long floored = price - (price % tick);
        // 경계에서 한 구간 아래로 내려가면 그 구간의 단위로 다시 맞춘다
        return floored > 0 && tickSizeOf(floored) != tick ? floorToTick(floored) : floored;
    }

    /**
     * 가격제한폭 안인가.
     *
     * @param referencePrice 기준가. 0 이하면 제한을 걸 근거가 없으므로 통과시킨다
     * @param limitPercent   기준가 대비 허용 등락률(%)
     */
    public static boolean isWithinDailyLimit(long price, long referencePrice, int limitPercent) {
        if (referencePrice <= 0) {
            return true;
        }
        if (limitPercent <= 0 || limitPercent >= 100) {
            throw new IllegalArgumentException("가격제한폭은 1~99% 여야 한다: " + limitPercent);
        }
        return price >= lowerLimit(referencePrice, limitPercent)
                && price <= upperLimit(referencePrice, limitPercent);
    }

    /** 상한가 — 올림하지 않고 버린다. 제한폭을 넘기는 쪽으로 반올림하면 제한이 헐거워진다. */
    public static long upperLimit(long referencePrice, int limitPercent) {
        return referencePrice + referencePrice / 100 * limitPercent
                + referencePrice % 100 * limitPercent / 100;
    }

    /** 하한가 — 최소 1원. 0원 호가는 의미가 없다. */
    public static long lowerLimit(long referencePrice, int limitPercent) {
        long down = referencePrice / 100 * limitPercent + referencePrice % 100 * limitPercent / 100;
        return Math.max(1, referencePrice - down);
    }
}

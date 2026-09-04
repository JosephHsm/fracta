package com.fracta.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fracta.common.money.Money;

/**
 * 수수료 정책 (ST-03) — 순수 함수. 체결금액 × 0.015%, <b>원 단위 절사</b>, 매수·매도 각각.
 * 단, <b>최소 {@value #MIN_FEE_WON}원</b>이다.
 *
 * <p>부동소수점을 쓰지 않는다. {@code BigDecimal}로 계산하고 {@link RoundingMode#DOWN}으로
 * 절사한 뒤 {@code long}으로 되돌린다. {@code double}로 곱하면 0.1원 단위에서 값이 흔들린다.
 */
public final class FeePolicy {

    /** 0.015% = 0.00015 */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.00015");

    /**
     * 체결 1건당 최소 수수료.
     *
     * <p>요율만 두고 절사하면 체결금액 6,666원 이하는 수수료가 <b>0원</b>이다. 주문을 잘게
     * 쪼개면 완전히 회피된다 — 조각투자는 소액 체결이 기본이라 이건 예외가 아니라 다수 케이스다.
     * 실제 증권사 수수료 체계에 최소수수료가 있는 이유가 정확히 이것이다.
     *
     * <p>{@link #FEE_RATE}와 마찬가지로 정책 상수다. 요율이 설정값이 아니므로 최소액만
     * 설정으로 빼면 두 값의 성격이 갈린다.
     */
    public static final long MIN_FEE_WON = 10;

    private FeePolicy() {
    }

    /**
     * 체결 1건의 수수료 = {@code min(체결금액, max(최소수수료, 절사(체결금액 × 요율)))}.
     *
     * <p>바깥 {@code min}은 수수료가 체결금액을 넘지 못하게 하는 가드다. 100원짜리 체결에
     * 최소수수료를 그대로 물리면 대금보다 수수료가 커지는 구간이 생긴다.
     */
    public static Money fee(Money executionAmount) {
        if (executionAmount.isZero()) {
            return Money.ZERO;
        }
        BigDecimal raw = executionAmount.toDisplay().multiply(FEE_RATE);
        long rated = raw.setScale(0, RoundingMode.DOWN).longValueExact();
        return Money.of(Math.min(executionAmount.amount(), Math.max(MIN_FEE_WON, rated)));
    }

    /** 체결금액 = 체결가 × 체결량. 곱셈 시점에 오버플로우를 잡는다. */
    public static Money executionAmount(Money price, long units) {
        return price.multiply(units);
    }
}

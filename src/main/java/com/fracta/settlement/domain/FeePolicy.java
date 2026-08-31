package com.fracta.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fracta.common.money.Money;

/**
 * 수수료 정책 (ST-03) — 순수 함수. 체결금액 × 0.015%, <b>원 단위 절사</b>, 매수·매도 각각.
 *
 * <p>부동소수점을 쓰지 않는다. {@code BigDecimal}로 계산하고 {@link RoundingMode#DOWN}으로
 * 절사한 뒤 {@code long}으로 되돌린다. {@code double}로 곱하면 0.1원 단위에서 값이 흔들린다.
 */
public final class FeePolicy {

    /** 0.015% = 0.00015 */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.00015");

    private FeePolicy() {
    }

    public static Money fee(Money executionAmount) {
        BigDecimal raw = executionAmount.toDisplay().multiply(FEE_RATE);
        return Money.of(raw.setScale(0, RoundingMode.DOWN).longValueExact());
    }

    /** 체결금액 = 체결가 × 체결량. 곱셈 시점에 오버플로우를 잡는다. */
    public static Money executionAmount(Money price, long units) {
        return price.multiply(units);
    }
}

package com.fracta.common.money;

import java.math.BigDecimal;

/**
 * 금액 값 객체. 내부 표현은 원 단위 {@code long} 고정 — 부동소수점·BigDecimal 내부 표현 금지.
 * 표시용 변환만 {@link #toDisplay()}로 BigDecimal을 반환한다.
 */
public record Money(long amount) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("금액은 음수가 될 수 없다: " + amount);
        }
    }

    public static Money of(long won) {
        return new Money(won);
    }

    public Money plus(Money other) {
        try {
            return new Money(Math.addExact(amount, other.amount));
        } catch (ArithmeticException e) {
            throw new AmountOverflowException("Money.plus", e);
        }
    }

    public Money minus(Money other) {
        return new Money(amount - other.amount);
    }

    public Money multiply(long units) {
        try {
            return new Money(Math.multiplyExact(amount, units));
        } catch (ArithmeticException e) {
            throw new AmountOverflowException("Money.multiply", e);
        }
    }

    public boolean isZero() {
        return amount == 0;
    }

    public BigDecimal toDisplay() {
        return BigDecimal.valueOf(amount);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(amount, other.amount);
    }
}

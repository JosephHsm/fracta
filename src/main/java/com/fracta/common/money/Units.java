package com.fracta.common.money;

import java.math.BigDecimal;

/**
 * 조각 수량 값 객체. 정수 {@code long} 고정, 음수 불허.
 */
public record Units(long value) implements Comparable<Units> {

    public static final Units ZERO = new Units(0);

    public Units {
        if (value < 0) {
            throw new IllegalArgumentException("수량은 음수가 될 수 없다: " + value);
        }
    }

    public static Units of(long value) {
        return new Units(value);
    }

    public Units plus(Units other) {
        try {
            return new Units(Math.addExact(value, other.value));
        } catch (ArithmeticException e) {
            throw new AmountOverflowException("Units.plus", e);
        }
    }

    public Units minus(Units other) {
        if (value < other.value) {
            throw new InsufficientUnitsException(value, other.value);
        }
        return new Units(value - other.value);
    }

    public boolean isZero() {
        return value == 0;
    }

    public BigDecimal toDisplay() {
        return BigDecimal.valueOf(value);
    }

    @Override
    public int compareTo(Units other) {
        return Long.compare(value, other.value);
    }
}

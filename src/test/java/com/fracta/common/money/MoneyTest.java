package com.fracta.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("경계값 생성: 0, 1, Long.MAX_VALUE")
    void createBoundaryValues() {
        assertThat(Money.of(0).amount()).isZero();
        assertThat(Money.of(0)).isEqualTo(Money.ZERO);
        assertThat(Money.of(1).amount()).isEqualTo(1L);
        assertThat(Money.of(Long.MAX_VALUE).amount()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("음수 금액은 생성 자체를 거부한다")
    void rejectNegativeAmount() {
        assertThatThrownBy(() -> Money.of(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plus() {
        assertThat(Money.of(1_000).plus(Money.of(234))).isEqualTo(Money.of(1_234));
        assertThat(Money.of(0).plus(Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("plus 오버플로우 시 AmountOverflowException")
    void plusOverflow() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).plus(Money.of(1)))
                .isInstanceOf(AmountOverflowException.class);
    }

    @Test
    void minus() {
        assertThat(Money.of(1_000).minus(Money.of(400))).isEqualTo(Money.of(600));
        assertThat(Money.of(1_000).minus(Money.of(1_000))).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("minus 결과가 음수면 거부한다")
    void minusRejectsNegativeResult() {
        assertThatThrownBy(() -> Money.of(100).minus(Money.of(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiply() {
        assertThat(Money.of(10_000).multiply(3)).isEqualTo(Money.of(30_000));
        assertThat(Money.of(10_000).multiply(0)).isEqualTo(Money.ZERO);
        assertThat(Money.of(Long.MAX_VALUE).multiply(1).amount()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("multiply 오버플로우 시 AmountOverflowException — 곱셈 시점에 잡는다")
    void multiplyOverflow() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).multiply(2))
                .isInstanceOf(AmountOverflowException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE / 2 + 1).multiply(2))
                .isInstanceOf(AmountOverflowException.class);
    }

    @Test
    @DisplayName("toDisplay는 BigDecimal을 반환한다")
    void toDisplay() {
        assertThat(Money.of(12_345).toDisplay()).isEqualByComparingTo(new BigDecimal("12345"));
        assertThat(Money.of(Long.MAX_VALUE).toDisplay())
                .isEqualByComparingTo(BigDecimal.valueOf(Long.MAX_VALUE));
    }

    @Test
    void compareTo() {
        assertThat(Money.of(1)).isGreaterThan(Money.ZERO);
        assertThat(Money.of(1)).isLessThan(Money.of(2));
        assertThat(Money.of(5)).isEqualByComparingTo(Money.of(5));
    }
}

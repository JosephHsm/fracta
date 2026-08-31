package com.fracta.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnitsTest {

    @Test
    @DisplayName("경계값 생성: 0, 1, Long.MAX_VALUE")
    void createBoundaryValues() {
        assertThat(Units.of(0)).isEqualTo(Units.ZERO);
        assertThat(Units.of(1).value()).isEqualTo(1L);
        assertThat(Units.of(Long.MAX_VALUE).value()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("음수 수량은 생성 자체를 거부한다")
    void rejectNegativeValue() {
        assertThatThrownBy(() -> Units.of(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Units.of(Long.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plus() {
        assertThat(Units.of(3).plus(Units.of(4))).isEqualTo(Units.of(7));
    }

    @Test
    @DisplayName("plus 오버플로우 시 AmountOverflowException")
    void plusOverflow() {
        assertThatThrownBy(() -> Units.of(Long.MAX_VALUE).plus(Units.of(1)))
                .isInstanceOf(AmountOverflowException.class);
    }

    @Test
    void minus() {
        assertThat(Units.of(5).minus(Units.of(3))).isEqualTo(Units.of(2));
        assertThat(Units.of(5).minus(Units.of(5))).isEqualTo(Units.ZERO);
    }

    @Test
    @DisplayName("보유보다 큰 수량 차감 시 InsufficientUnitsException")
    void minusInsufficient() {
        assertThatThrownBy(() -> Units.of(3).minus(Units.of(5)))
                .isInstanceOf(InsufficientUnitsException.class);
        assertThatThrownBy(() -> Units.ZERO.minus(Units.of(1)))
                .isInstanceOf(InsufficientUnitsException.class);
    }

    @Test
    void compareTo() {
        assertThat(Units.of(1)).isGreaterThan(Units.ZERO);
        assertThat(Units.of(1)).isLessThan(Units.of(2));
        assertThat(Units.of(5)).isEqualByComparingTo(Units.of(5));
    }
}

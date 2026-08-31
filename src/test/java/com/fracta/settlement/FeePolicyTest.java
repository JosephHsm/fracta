package com.fracta.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.money.AmountOverflowException;
import com.fracta.common.money.Money;
import com.fracta.settlement.domain.FeePolicy;

/** 수수료 0.015%, 원 단위 절사 (ST-03). 부동소수점 금지. */
class FeePolicyTest {

    @Test
    @DisplayName("기본 계산 — 100만원 × 0.015% = 150원")
    void basicFee() {
        assertThat(FeePolicy.fee(Money.of(1_000_000))).isEqualTo(Money.of(150));
    }

    @Test
    @DisplayName("절사 경계 — 소수점 이하는 버린다 (반올림 아님)")
    void truncatesDown() {
        // 6,666 × 0.00015 = 0.9999 → 0원
        assertThat(FeePolicy.fee(Money.of(6_666))).isEqualTo(Money.ZERO);
        // 6,667 × 0.00015 = 1.00005 → 1원
        assertThat(FeePolicy.fee(Money.of(6_667))).isEqualTo(Money.of(1));
        // 13,333 × 0.00015 = 1.99995 → 1원 (반올림이면 2원이 됐을 값)
        assertThat(FeePolicy.fee(Money.of(13_333))).isEqualTo(Money.of(1));
        // 13,334 × 0.00015 = 2.0001 → 2원
        assertThat(FeePolicy.fee(Money.of(13_334))).isEqualTo(Money.of(2));
    }

    @Test
    @DisplayName("소액 체결 — 수수료가 0원이 될 수 있다")
    void tinyAmountsYieldZeroFee() {
        assertThat(FeePolicy.fee(Money.ZERO)).isEqualTo(Money.ZERO);
        assertThat(FeePolicy.fee(Money.of(1))).isEqualTo(Money.ZERO);
        assertThat(FeePolicy.fee(Money.of(6_665))).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("큰 금액 — 정확히 계산된다 (double 이면 오차가 난다)")
    void largeAmounts() {
        assertThat(FeePolicy.fee(Money.of(10_000_000_000L))).isEqualTo(Money.of(1_500_000));
        // 0.00015 를 double 로 곱하면 마지막 자리가 흔들리는 값
        assertThat(FeePolicy.fee(Money.of(999_999_999L))).isEqualTo(Money.of(149_999));
    }

    @Test
    @DisplayName("체결금액 = 가격 × 수량, 오버플로우는 곱셈 시점에 잡는다")
    void executionAmountOverflow() {
        assertThat(FeePolicy.executionAmount(Money.of(1_000), 30)).isEqualTo(Money.of(30_000));
        assertThatThrownBy(() -> FeePolicy.executionAmount(Money.of(Long.MAX_VALUE), 2))
                .isInstanceOf(AmountOverflowException.class);
    }
}

package com.fracta.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.money.AmountOverflowException;
import com.fracta.common.money.Money;
import com.fracta.settlement.domain.FeePolicy;

/** 수수료 0.015%, 원 단위 절사, 최소 10원 (ST-03). 부동소수점 금지. */
class FeePolicyTest {

    @Test
    @DisplayName("기본 계산 — 100만원 × 0.015% = 150원")
    void basicFee() {
        assertThat(FeePolicy.fee(Money.of(1_000_000))).isEqualTo(Money.of(150));
    }

    @Test
    @DisplayName("절사 경계 — 소수점 이하는 버린다 (반올림 아님)")
    void truncatesDown() {
        // 요율 계산이 최소수수료를 넘는 구간에서 절사 규칙을 본다.
        // 66,666 × 0.00015 = 9.9999 → 9원이지만 최소 10원이 걸린다
        assertThat(FeePolicy.fee(Money.of(66_666))).isEqualTo(Money.of(10));
        // 73,333 × 0.00015 = 10.99995 → 10원 (반올림이면 11원이 됐을 값)
        assertThat(FeePolicy.fee(Money.of(73_333))).isEqualTo(Money.of(10));
        // 73,334 × 0.00015 = 11.0001 → 11원
        assertThat(FeePolicy.fee(Money.of(73_334))).isEqualTo(Money.of(11));
    }

    @Test
    @DisplayName("소액 체결에도 최소수수료가 걸린다 — 분할 체결로 회피할 수 없다")
    void smallAmountsPayMinimumFee() {
        // 요율만 두면 6,666원 이하는 전부 0원이었다. 주문을 쪼개면 수수료가 사라졌다.
        assertThat(FeePolicy.fee(Money.of(6_666))).isEqualTo(Money.of(FeePolicy.MIN_FEE_WON));
        assertThat(FeePolicy.fee(Money.of(1_000))).isEqualTo(Money.of(FeePolicy.MIN_FEE_WON));
        assertThat(FeePolicy.fee(Money.of(100))).isEqualTo(Money.of(FeePolicy.MIN_FEE_WON));

        // 쪼개도 총액이 줄지 않는다 — 10,000원 한 번 vs 1,000원 열 번
        assertThat(FeePolicy.fee(Money.of(10_000)).amount())
                .isLessThanOrEqualTo(FeePolicy.fee(Money.of(1_000)).amount() * 10);
    }

    @Test
    @DisplayName("수수료가 체결금액을 넘지 않는다")
    void feeNeverExceedsExecutionAmount() {
        assertThat(FeePolicy.fee(Money.ZERO)).isEqualTo(Money.ZERO);
        assertThat(FeePolicy.fee(Money.of(1))).isEqualTo(Money.of(1));
        assertThat(FeePolicy.fee(Money.of(9))).isEqualTo(Money.of(9));
        assertThat(FeePolicy.fee(Money.of(10))).isEqualTo(Money.of(10));
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

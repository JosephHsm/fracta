package com.fracta.external.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.money.Money;

/** FSD §9.4 조각 환산·괴리율 — 분할비율 경계값, 반올림, 원자산가 0 처리. */
class PriceConverterTest {

    @Test
    @DisplayName("FSD 예시 그대로: 4,200원 / 100조각 → 참조가 42원")
    void fsdExample() {
        assertThat(PriceConverter.referencePrice(Money.of(4_200), 100)).isEqualTo(Money.of(42));
    }

    @Test
    @DisplayName("반올림은 HALF_UP 고정")
    void roundingIsHalfUp() {
        // 125/100 = 1.25 → 1
        assertThat(PriceConverter.referencePrice(Money.of(125), 100)).isEqualTo(Money.of(1));
        // 150/100 = 1.5 → 2 (HALF_UP)
        assertThat(PriceConverter.referencePrice(Money.of(150), 100)).isEqualTo(Money.of(2));
        // 149/100 = 1.49 → 1
        assertThat(PriceConverter.referencePrice(Money.of(149), 100)).isEqualTo(Money.of(1));
        // 250/100 = 2.5 → 3 (HALF_UP은 항상 올림, HALF_EVEN이면 2가 된다)
        assertThat(PriceConverter.referencePrice(Money.of(250), 100)).isEqualTo(Money.of(3));
    }

    @Test
    @DisplayName("분할비율 경계값: 1(분할 안 함), 매우 큰 값이면 최소 1원")
    void splitRatioBoundaries() {
        assertThat(PriceConverter.referencePrice(Money.of(4_200), 1)).isEqualTo(Money.of(4_200));
        // 4,200 / 100,000 = 0.042 → 반올림 0이지만 0원 조각은 무의미하므로 1원으로 올린다
        assertThat(PriceConverter.referencePrice(Money.of(4_200), 100_000)).isEqualTo(Money.of(1));
        assertThatThrownBy(() -> PriceConverter.referencePrice(Money.of(4_200), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceConverter.referencePrice(Money.of(4_200), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("원자산 가격 0이면 참조가 0, 괴리율은 계산하지 않는다 (0으로 나누기 방지)")
    void zeroUnderlyingSkipsPremium() {
        assertThat(PriceConverter.referencePrice(Money.ZERO, 100)).isEqualTo(Money.ZERO);
        assertThat(PriceConverter.premiumRate(Money.of(50), Money.ZERO)).isEmpty();
        assertThat(PriceConverter.premiumRate(Money.of(50), Money.ZERO, 100)).isEmpty();
    }

    @Test
    @DisplayName("괴리율 계산: 참조가 42원 대비 체결가 50원 → +19.0476%")
    void premiumRate() {
        var premium = PriceConverter.premiumRate(Money.of(50), Money.of(42));
        assertThat(premium).isPresent();
        assertThat(premium.get()).isEqualByComparingTo(new BigDecimal("19.0476"));
    }

    @Test
    @DisplayName("괴리율 부호: 체결가가 낮으면 음수, 같으면 0")
    void premiumSign() {
        assertThat(PriceConverter.premiumRate(Money.of(21), Money.of(42)).orElseThrow())
                .isEqualByComparingTo(new BigDecimal("-50.0000"));
        assertThat(PriceConverter.premiumRate(Money.of(42), Money.of(42)).orElseThrow())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("원자산가·분할비율에서 곧바로 괴리율 산출")
    void premiumFromUnderlying() {
        // 4,200 / 100 = 42, 체결가 42 → 0%
        assertThat(PriceConverter.premiumRate(Money.of(42), Money.of(4_200), 100).orElseThrow())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}

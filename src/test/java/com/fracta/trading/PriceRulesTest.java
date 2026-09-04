package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fracta.trading.domain.PriceRules;

/** 호가단위·가격제한폭 순수 단위 테스트 (TR-01/02). */
class PriceRulesTest {

    @ParameterizedTest
    @CsvSource({
            "1,          1",
            "999,        1",
            "1000,      10",
            "9990,      10",
            "10000,     50",
            "99950,     50",
            "100000,   100",
            "999900,   100",
            "1000000, 1000",
            "5000000, 1000",
    })
    @DisplayName("호가단위는 가격대별로 커진다")
    void tickSizeByBand(long price, long expectedTick) {
        assertThat(PriceRules.tickSizeOf(price)).isEqualTo(expectedTick);
    }

    @Test
    @DisplayName("호가단위에 맞지 않는 가격은 걸러낸다")
    void rejectsOffTickPrices() {
        assertThat(PriceRules.isOnTick(1_005)).isFalse();   // 1,000원대는 10원 단위
        assertThat(PriceRules.isOnTick(1_010)).isTrue();
        assertThat(PriceRules.isOnTick(10_025)).isFalse();  // 1만원대는 50원 단위
        assertThat(PriceRules.isOnTick(10_050)).isTrue();
        assertThat(PriceRules.isOnTick(0)).isFalse();
    }

    @Test
    @DisplayName("호가단위 보정은 내림이다 — 조용히 올리면 사용자에게 불리하다")
    void floorsToTick() {
        assertThat(PriceRules.floorToTick(1_009)).isEqualTo(1_000);
        assertThat(PriceRules.floorToTick(10_049)).isEqualTo(10_000);
        assertThat(PriceRules.floorToTick(999)).isEqualTo(999);   // 1원 단위 구간
    }

    @Test
    @DisplayName("구간 경계에서 내림하면 아래 구간의 단위로 다시 맞춘다")
    void floorCrossingBandBoundary() {
        // 10,000은 50원 단위 구간의 시작. 10,040을 50으로 내리면 10,000이라 그대로다
        assertThat(PriceRules.floorToTick(10_040)).isEqualTo(10_000);
        assertThat(PriceRules.isOnTick(PriceRules.floorToTick(10_040))).isTrue();
        // 1,005 → 1,000 (10원 단위). 결과가 다시 호가단위에 맞는지 본다
        assertThat(PriceRules.isOnTick(PriceRules.floorToTick(1_005))).isTrue();
    }

    @Test
    @DisplayName("가격제한폭 — 기준가 ±30% 밖은 거부한다")
    void dailyLimitBand() {
        long reference = 10_000;

        assertThat(PriceRules.upperLimit(reference, 30)).isEqualTo(13_000);
        assertThat(PriceRules.lowerLimit(reference, 30)).isEqualTo(7_000);

        assertThat(PriceRules.isWithinDailyLimit(13_000, reference, 30)).isTrue();
        assertThat(PriceRules.isWithinDailyLimit(13_001, reference, 30)).isFalse();
        assertThat(PriceRules.isWithinDailyLimit(7_000, reference, 30)).isTrue();
        assertThat(PriceRules.isWithinDailyLimit(6_999, reference, 30)).isFalse();
    }

    @Test
    @DisplayName("제한폭 계산은 절사한다 — 반올림하면 제한이 헐거워진다")
    void limitsTruncate() {
        // 1,001 × 30% = 300.3 → 300
        assertThat(PriceRules.upperLimit(1_001, 30)).isEqualTo(1_301);
        assertThat(PriceRules.lowerLimit(1_001, 30)).isEqualTo(701);
    }

    @Test
    @DisplayName("하한가는 최소 1원 — 0원 호가는 의미가 없다")
    void lowerLimitNeverZero() {
        assertThat(PriceRules.lowerLimit(1, 99)).isEqualTo(1);
        assertThat(PriceRules.lowerLimit(2, 99)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("기준가가 없으면(0) 제한을 걸 근거가 없어 통과시킨다")
    void noReferenceMeansNoLimit() {
        assertThat(PriceRules.isWithinDailyLimit(999_999, 0, 30)).isTrue();
    }

    @Test
    @DisplayName("큰 기준가에서도 오버플로우 없이 계산된다")
    void largeReferenceDoesNotOverflow() {
        long reference = 9_000_000_000L;

        assertThat(PriceRules.upperLimit(reference, 30)).isEqualTo(11_700_000_000L);
        assertThat(PriceRules.lowerLimit(reference, 30)).isEqualTo(6_300_000_000L);
    }

    @Test
    @DisplayName("잘못된 입력은 거부한다")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> PriceRules.tickSizeOf(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceRules.isWithinDailyLimit(100, 1_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceRules.isWithinDailyLimit(100, 1_000, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

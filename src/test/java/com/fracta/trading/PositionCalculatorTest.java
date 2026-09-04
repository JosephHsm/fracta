package com.fracta.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.trading.domain.PositionCalculator;
import com.fracta.trading.domain.PositionCalculator.Lot;

/** 취득원가 재생 (이동평균) 순수 단위 테스트. */
class PositionCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    private static Instant at(long minutes) {
        return T0.plusSeconds(minutes * 60);
    }

    @Test
    @DisplayName("매수만 있으면 원가는 단순 합 — 수수료도 포함한다")
    void acquisitionsAccumulate() {
        var result = PositionCalculator.replay(List.of(
                Lot.acquire(at(0), 10, 1_000, 1),
                Lot.acquire(at(1), 5, 1_200, 0)));

        assertThat(result.units()).isEqualTo(15);
        // 10×1,000 + 1 + 5×1,200 = 16,001
        assertThat(result.costBasis()).isEqualTo(16_001);
    }

    @Test
    @DisplayName("일부 매도 — 그 시점 평균단가만큼만 원가를 덜어낸다")
    void disposalRemovesAverageCost() {
        var result = PositionCalculator.replay(List.of(
                Lot.acquire(at(0), 10, 1_000, 0),   // 원가 10,000 / 10주
                Lot.dispose(at(1), 5)));            // 절반 처분 → 원가도 절반

        assertThat(result.units()).isEqualTo(5);
        assertThat(result.costBasis()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("서로 다른 가격에 사고 일부 팔면 남은 수량에 평균단가가 묻는다")
    void movingAverageAcrossPrices() {
        var result = PositionCalculator.replay(List.of(
                Lot.acquire(at(0), 10, 1_000, 0),   // 10,000
                Lot.acquire(at(1), 10, 2_000, 0),   // 20,000 → 20주 30,000 (평균 1,500)
                Lot.dispose(at(2), 10)));           // 10주 처분 → 15,000 차감

        assertThat(result.units()).isEqualTo(10);
        assertThat(result.costBasis()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("전량 처분하면 원가가 남김없이 사라진다 — 절사 잔액이 남으면 안 된다")
    void fullDisposalClearsCost() {
        var result = PositionCalculator.replay(List.of(
                Lot.acquire(at(0), 3, 1_000, 7),    // 3,007 / 3주 — 나누어떨어지지 않는다
                Lot.dispose(at(1), 3)));

        assertThat(result.units()).isZero();
        assertThat(result.costBasis()).isZero();
    }

    @Test
    @DisplayName("샀다 팔았다를 반복해도 남은 수량과 원가가 어긋나지 않는다")
    void repeatedRoundTrips() {
        var result = PositionCalculator.replay(List.of(
                Lot.acquire(at(0), 7, 1_100, 1),
                Lot.dispose(at(1), 3),
                Lot.acquire(at(2), 5, 900, 0),
                Lot.dispose(at(3), 4),
                Lot.acquire(at(4), 2, 1_500, 0)));

        // 7 - 3 + 5 - 4 + 2 = 7
        assertThat(result.units()).isEqualTo(7);
        assertThat(result.costBasis()).isPositive();
    }

    @Test
    @DisplayName("입력 순서가 뒤섞여 있어도 시간순으로 재생한다")
    void reordersByTime() {
        var shuffled = PositionCalculator.replay(List.of(
                Lot.dispose(at(2), 10),
                Lot.acquire(at(1), 10, 2_000, 0),
                Lot.acquire(at(0), 10, 1_000, 0)));

        assertThat(shuffled.units()).isEqualTo(10);
        assertThat(shuffled.costBasis()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("보유분을 넘는 처분 기록이 있어도 음수가 되지 않는다")
    void oversellDoesNotGoNegative() {
        var result = PositionCalculator.replay(List.of(
                Lot.acquire(at(0), 5, 1_000, 0),
                Lot.dispose(at(1), 9)));

        assertThat(result.units()).isZero();
        assertThat(result.costBasis()).isZero();
    }

    @Test
    @DisplayName("기록이 없으면 0주 0원")
    void emptyHistory() {
        var result = PositionCalculator.replay(List.of());

        assertThat(result.units()).isZero();
        assertThat(result.costBasis()).isZero();
    }
}

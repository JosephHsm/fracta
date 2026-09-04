package com.fracta.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.subscription.domain.HybridAllocator;
import com.fracta.subscription.domain.ProportionalAllocator;
import com.fracta.subscription.domain.ProportionalAllocator.AllotmentInput;

/**
 * 균등 + 비례 혼합 배정 (SB-03 확장).
 *
 * <p>순수 안분비례는 경쟁률이 높으면 소액 청약자가 0주를 받는다. 조각투자의 존재 이유가
 * 소액 접근성인데 배분 규칙이 반대를 봤다.
 */
class HybridAllocatorTest {

    private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");

    private static AllotmentInput input(long id, long requested, long secondsAfter) {
        return new AllotmentInput(id, requested, T0.plusSeconds(secondsAfter));
    }

    private static long sum(Map<Long, Long> allotment) {
        return allotment.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    @DisplayName("소액 청약자도 물량을 받는다 — 순수 안분이면 0주였을 경우")
    void smallApplicantGetsUnitsThatProrataWouldDenyThem() {
        // 총 100주에 대해 큰손 하나가 10,000주, 소액 9명이 1주씩 신청
        List<AllotmentInput> inputs = new java.util.ArrayList<>();
        inputs.add(input(1, 10_000, 0));
        for (long id = 2; id <= 10; id++) {
            inputs.add(input(id, 1, id));
        }

        Map<Long, Long> prorata = ProportionalAllocator.allocate(inputs, 100);
        Map<Long, Long> hybrid = HybridAllocator.allocate(inputs, 100, 50);

        // 순수 안분에서는 소액 신청자 대부분이 0주다
        assertThat(prorata.get(2L)).isZero();
        // 혼합에서는 균등 몫(50 ÷ 10 = 5주)까지 받는데, 신청량이 1주뿐이라 1주를 받는다
        assertThat(hybrid.get(2L)).isEqualTo(1);
        assertThat(sum(hybrid)).isEqualTo(100);
    }

    @Test
    @DisplayName("Σaᵢ == N 불변식은 그대로 성립한다")
    void totalIsPreserved() {
        List<AllotmentInput> inputs = List.of(
                input(1, 500, 0), input(2, 300, 1), input(3, 77, 2), input(4, 1, 3));

        for (int percent : new int[]{0, 10, 33, 50, 99, 100}) {
            Map<Long, Long> result = HybridAllocator.allocate(inputs, 137, percent);
            assertThat(sum(result))
                    .as("균등 비율 %d%%", percent)
                    .isEqualTo(137);
        }
    }

    @Test
    @DisplayName("신청량을 넘겨 배정하지 않는다 — 균등 몫이 신청량보다 커도")
    void neverAllocatesMoreThanRequested() {
        // 균등 몫은 1,000 × 80% ÷ 2 = 400주지만 2번은 3주만 신청했다
        List<AllotmentInput> inputs = List.of(input(1, 5_000, 0), input(2, 3, 1));

        Map<Long, Long> result = HybridAllocator.allocate(inputs, 1_000, 80);

        assertThat(result.get(2L)).isEqualTo(3);
        assertThat(result.get(1L)).isEqualTo(997);
        assertThat(sum(result)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("균등 비율 0%는 순수 안분비례와 같다")
    void zeroPercentEqualsProrata() {
        List<AllotmentInput> inputs = List.of(
                input(1, 700, 0), input(2, 200, 1), input(3, 100, 2));

        assertThat(HybridAllocator.allocate(inputs, 250, 0))
                .isEqualTo(ProportionalAllocator.allocate(inputs, 250));
    }

    @Test
    @DisplayName("경쟁률 1 이하면 균등 단계를 건너뛴다 — 전원이 신청량을 다 받는다")
    void undersubscribedGivesEveryoneTheirRequest() {
        List<AllotmentInput> inputs = List.of(input(1, 30, 0), input(2, 20, 1));

        Map<Long, Long> result = HybridAllocator.allocate(inputs, 100, 50);

        assertThat(result.get(1L)).isEqualTo(30);
        assertThat(result.get(2L)).isEqualTo(20);
    }

    @Test
    @DisplayName("같은 입력이면 항상 같은 결과 — 결정론")
    void deterministic() {
        List<AllotmentInput> inputs = List.of(
                input(1, 333, 0), input(2, 333, 1), input(3, 334, 2), input(4, 7, 3));

        Map<Long, Long> first = HybridAllocator.allocate(inputs, 251, 40);
        for (int i = 0; i < 20; i++) {
            assertThat(HybridAllocator.allocate(inputs, 251, 40)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("균등 비율 범위를 벗어나면 거부한다")
    void rejectsInvalidPercent() {
        List<AllotmentInput> inputs = List.of(input(1, 10, 0));

        assertThatThrownBy(() -> HybridAllocator.allocate(inputs, 5, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HybridAllocator.allocate(inputs, 5, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("큰 총량에서도 균등 몫 계산이 오버플로우 없이 정확하다")
    void largeTotalsDoNotOverflow() {
        List<AllotmentInput> inputs = List.of(
                input(1, 900_000, 0), input(2, 900_000, 1));

        // 1,000,000 × 60% = 600,000 → 균등 몫 300,000씩
        Map<Long, Long> result = HybridAllocator.allocate(inputs, 1_000_000, 60);

        assertThat(sum(result)).isEqualTo(1_000_000);
        assertThat(result.get(1L)).isEqualTo(500_000);
        assertThat(result.get(2L)).isEqualTo(500_000);
    }
}

package com.fracta.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.subscription.domain.ProportionalAllocator;
import com.fracta.subscription.domain.ProportionalAllocator.AllotmentInput;

class ProportionalAllocatorTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    @DisplayName("불변식 Σaᵢ == N — 무작위 입력 반복 검증")
    void sumAlwaysEqualsTotal() {
        for (int round = 0; round < 50; round++) {
            List<AllotmentInput> inputs = new ArrayList<>();
            int count = ThreadLocalRandom.current().nextInt(1, 60);
            for (int i = 0; i < count; i++) {
                inputs.add(new AllotmentInput(i + 1,
                        ThreadLocalRandom.current().nextLong(1, 10_000),
                        T0.plusSeconds(ThreadLocalRandom.current().nextInt(0, 1_000))));
            }
            long total = ThreadLocalRandom.current().nextLong(1, 5_000);
            Map<Long, Long> result = ProportionalAllocator.allocate(inputs, total);

            long sum = result.values().stream().mapToLong(Long::longValue).sum();
            long requestedSum = inputs.stream().mapToLong(AllotmentInput::requested).sum();
            assertThat(sum).isEqualTo(Math.min(total, requestedSum));
            // 개별 배정은 신청량을 넘지 않는다
            inputs.forEach(in -> assertThat(result.get(in.id())).isLessThanOrEqualTo(in.requested()));
        }
    }

    @Test
    @DisplayName("결정론 — 동일 입력 100회 실행 → 100회 동일 결과")
    void deterministic100Runs() {
        List<AllotmentInput> inputs = List.of(
                new AllotmentInput(3, 77, T0.plusSeconds(2)),
                new AllotmentInput(1, 501, T0.plusSeconds(1)),
                new AllotmentInput(7, 123, T0),
                new AllotmentInput(2, 999, T0.plusSeconds(3)),
                new AllotmentInput(9, 250, T0.plusSeconds(1)));
        Map<Long, Long> first = ProportionalAllocator.allocate(inputs, 700);
        for (int i = 0; i < 100; i++) {
            assertThat(ProportionalAllocator.allocate(inputs, 700)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("단수주 극단 케이스 — T=3, N=2, rᵢ=[1,1,1]: 앞선 신청 2건이 1개씩")
    void oddLotExtremeCase() {
        List<AllotmentInput> inputs = List.of(
                new AllotmentInput(10, 1, T0.plusSeconds(3)),
                new AllotmentInput(11, 1, T0.plusSeconds(1)),
                new AllotmentInput(12, 1, T0.plusSeconds(2)));
        Map<Long, Long> result = ProportionalAllocator.allocate(inputs, 2);

        // base = floor(1×2/3) = 0 전원, 잔여 2개 → 소수부 동률 → 신청 시각 빠른 순
        assertThat(result.get(11L)).isEqualTo(1);
        assertThat(result.get(12L)).isEqualTo(1);
        assertThat(result.get(10L)).isEqualTo(0);
        assertThat(result.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(2);
    }

    @Test
    @DisplayName("오버플로우 — rᵢ×N이 long을 초과해도 정확 (BigInteger 경유)")
    void largeValuesDoNotOverflow() {
        long big = Long.MAX_VALUE / 2;
        List<AllotmentInput> inputs = List.of(
                new AllotmentInput(1, big, T0),
                new AllotmentInput(2, big - 1, T0.plusSeconds(1)));
        long total = 1_000_000;
        Map<Long, Long> result = ProportionalAllocator.allocate(inputs, total);

        assertThat(result.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
        // 신청량이 1 차이 → 배정도 거의 절반씩
        assertThat(result.get(1L)).isBetween(total / 2 - 1, total / 2 + 1);
        assertThat(result.get(2L)).isBetween(total / 2 - 1, total / 2 + 1);
    }

    @Test
    @DisplayName("tie-breaker 1단계 — 소수부 큰 쪽이 잔여를 가져간다")
    void tieBreakerByRemainder() {
        // N=10, T=16: id1 r=11 → 6.875 (base 6, rem 14/16), id2 r=5 → 3.125 (base 3, rem 2/16)
        List<AllotmentInput> inputs = List.of(
                new AllotmentInput(1, 11, T0.plusSeconds(9)),   // 시각 늦어도 소수부가 크면 우선
                new AllotmentInput(2, 5, T0));
        Map<Long, Long> result = ProportionalAllocator.allocate(inputs, 10);
        assertThat(result.get(1L)).isEqualTo(7);
        assertThat(result.get(2L)).isEqualTo(3);
    }

    @Test
    @DisplayName("tie-breaker 2단계 — 소수부 동률이면 신청 시각 빠른 순")
    void tieBreakerByAppliedAt() {
        // N=3, T=6(2+2+2): base 1씩, rem 동률 → 잔여 0... 대신 N=4, T=6: base=1 rem=2/6 동률 3건, 잔여 1
        List<AllotmentInput> inputs = List.of(
                new AllotmentInput(1, 2, T0.plusSeconds(5)),
                new AllotmentInput(2, 2, T0.plusSeconds(1)),   // 가장 빠름 → +1
                new AllotmentInput(3, 2, T0.plusSeconds(3)));
        Map<Long, Long> result = ProportionalAllocator.allocate(inputs, 4);
        assertThat(result.get(2L)).isEqualTo(2);
        assertThat(result.get(1L)).isEqualTo(1);
        assertThat(result.get(3L)).isEqualTo(1);
    }

    @Test
    @DisplayName("tie-breaker 3단계 — 시각까지 동률이면 청약ID 오름차순")
    void tieBreakerById() {
        List<AllotmentInput> inputs = List.of(
                new AllotmentInput(30, 2, T0),
                new AllotmentInput(10, 2, T0),   // 동시각 → ID 낮은 쪽 우선
                new AllotmentInput(20, 2, T0));
        Map<Long, Long> result = ProportionalAllocator.allocate(inputs, 4);
        assertThat(result.get(10L)).isEqualTo(2);
        assertThat(result.get(20L)).isEqualTo(1);
        assertThat(result.get(30L)).isEqualTo(1);
    }

    @Test
    @DisplayName("경쟁률 1 이하 — 전원 신청량 그대로")
    void underSubscribedGetsFull() {
        List<AllotmentInput> inputs = List.of(
                new AllotmentInput(1, 30, T0),
                new AllotmentInput(2, 20, T0));
        Map<Long, Long> result = ProportionalAllocator.allocate(inputs, 100);
        assertThat(result.get(1L)).isEqualTo(30);
        assertThat(result.get(2L)).isEqualTo(20);
    }

    @Test
    @DisplayName("잘못된 입력 거부")
    void invalidInputs() {
        assertThatThrownBy(() -> ProportionalAllocator.allocate(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProportionalAllocator.allocate(
                List.of(new AllotmentInput(1, 0, T0)), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

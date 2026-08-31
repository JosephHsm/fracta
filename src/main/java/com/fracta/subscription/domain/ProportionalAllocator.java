package com.fracta.subscription.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 비례배분 (FSD §8.3 SB-03) — 순수 함수. DB 없이 단위 테스트한다.
 *
 * <p>결정론 보장:
 * <ul>
 *   <li>소수부 비교는 부동소수점 금지 — {@code (rᵢ × N) mod T}를 BigInteger로 비교한다</li>
 *   <li>tie-breaker 3단계: 소수부 큰 순 → 신청 시각 빠른 순 → 청약ID 오름차순</li>
 *   <li>{@code rᵢ × N} 오버플로우 방지 — 전 과정 BigInteger</li>
 * </ul>
 * 불변식 Σaᵢ == N을 위반하면 예외를 던진다.
 */
public final class ProportionalAllocator {

    public record AllotmentInput(long id, long requested, Instant appliedAt) {
    }

    private ProportionalAllocator() {
    }

    public static Map<Long, Long> allocate(List<AllotmentInput> inputs, long totalUnits) {
        if (totalUnits <= 0) {
            throw new IllegalArgumentException("총 발행량은 1 이상이어야 한다: " + totalUnits);
        }
        for (AllotmentInput input : inputs) {
            if (input.requested() <= 0) {
                throw new IllegalArgumentException("신청 수량은 1 이상이어야 한다: id=" + input.id());
            }
        }

        Map<Long, Long> result = new LinkedHashMap<>();
        if (inputs.isEmpty()) {
            return result;
        }

        BigInteger n = BigInteger.valueOf(totalUnits);
        BigInteger t = inputs.stream()
                .map(i -> BigInteger.valueOf(i.requested()))
                .reduce(BigInteger.ZERO, BigInteger::add);

        // 경쟁률 1 이하 — 전원 신청량 그대로
        if (t.compareTo(n) <= 0) {
            inputs.forEach(i -> result.put(i.id(), i.requested()));
            return result;
        }

        record Fraction(AllotmentInput input, long base, BigInteger remainder) {
        }

        List<Fraction> fractions = new ArrayList<>(inputs.size());
        long baseSum = 0;
        for (AllotmentInput input : inputs) {
            BigInteger product = BigInteger.valueOf(input.requested()).multiply(n);
            BigInteger[] divRem = product.divideAndRemainder(t);
            long base = divRem[0].longValueExact();
            fractions.add(new Fraction(input, base, divRem[1]));
            result.put(input.id(), base);
            baseSum = Math.addExact(baseSum, base);
        }

        long residual = totalUnits - baseSum;

        // 잔여 분배: 소수부 큰 순 → 신청 시각 빠른 순 → ID 오름차순 (전부 명시적, 안정적)
        fractions.sort(Comparator
                .comparing(Fraction::remainder, Comparator.reverseOrder())
                .thenComparing(f -> f.input().appliedAt())
                .thenComparingLong(f -> f.input().id()));
        for (int i = 0; i < residual; i++) {
            AllotmentInput winner = fractions.get(i).input();
            result.merge(winner.id(), 1L, Long::sum);
        }

        long allocatedSum = result.values().stream().mapToLong(Long::longValue).sum();
        if (allocatedSum != totalUnits) {
            throw new IllegalStateException(
                    "비례배분 불변식 위반: Σa=%d ≠ N=%d".formatted(allocatedSum, totalUnits));
        }
        return result;
    }
}

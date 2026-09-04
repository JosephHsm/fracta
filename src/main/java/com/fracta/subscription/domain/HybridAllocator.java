package com.fracta.subscription.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fracta.subscription.domain.ProportionalAllocator.AllotmentInput;

/**
 * 균등 + 비례 혼합 배정 (SB-03 확장) — 순수 함수.
 *
 * <p><b>왜 필요한가.</b> 순수 안분비례는 경쟁률이 높으면 소액 청약자가 0주를 받는다.
 * 조각투자의 존재 이유가 소액 접근성인데 배분 규칙이 반대를 봤다. 국내 공모주에 균등배정이
 * 도입된 배경이 정확히 이 문제다.
 *
 * <pre>
 * 1단계: 균등 몫 = floor(총량 × 균등비율 ÷ 100 ÷ 참여자수)
 *        신청량이 균등 몫보다 적으면 신청량까지만 받는다 (넘겨 주지 않는다)
 * 2단계: 남은 물량 = 총량 − 1단계 배분 합
 *        각자의 잔여 신청량(신청량 − 1단계 배분)으로 기존 비례배분을 돌린다
 * </pre>
 *
 * <p>기존 {@link ProportionalAllocator}를 그대로 재사용한다. 결정론과 불변식
 * {@code Σaᵢ == N}은 2단계가 이미 보장하므로 여기서는 1단계 합만 정확히 빼면 된다.
 *
 * <p>경쟁률이 1 이하면(총 신청량 ≤ 총량) 균등 단계를 건너뛴다 — 전원이 신청량을 다 받는데
 * 굳이 두 단계로 나눌 이유가 없다.
 */
public final class HybridAllocator {

    private HybridAllocator() {
    }

    /**
     * @param equalPercent 총량 중 균등 배분에 쓸 비율(0~100). 0이면 순수 비례배분과 같다
     */
    public static Map<Long, Long> allocate(List<AllotmentInput> inputs, long totalUnits,
                                           int equalPercent) {
        if (equalPercent < 0 || equalPercent > 100) {
            throw new IllegalArgumentException("균등 비율은 0~100 이어야 한다: " + equalPercent);
        }
        if (equalPercent == 0 || inputs.isEmpty()) {
            return ProportionalAllocator.allocate(inputs, totalUnits);
        }

        long requestedTotal = inputs.stream()
                .mapToLong(AllotmentInput::requested)
                .reduce(0, Math::addExact);
        if (requestedTotal <= totalUnits) {
            // 경쟁률 1 이하 — 균등 단계가 의미 없다
            return ProportionalAllocator.allocate(inputs, totalUnits);
        }

        long equalPool = totalUnits / 100 * equalPercent
                + (totalUnits % 100) * equalPercent / 100;
        long equalShare = equalPool / inputs.size();

        Map<Long, Long> result = new LinkedHashMap<>();
        List<AllotmentInput> remainders = new ArrayList<>(inputs.size());
        long distributed = 0;
        for (AllotmentInput input : inputs) {
            long given = Math.min(equalShare, input.requested());
            result.put(input.id(), given);
            distributed = Math.addExact(distributed, given);
            long left = input.requested() - given;
            if (left > 0) {
                remainders.add(new AllotmentInput(input.id(), left, input.appliedAt()));
            }
        }

        long rest = totalUnits - distributed;
        if (rest > 0 && !remainders.isEmpty()) {
            ProportionalAllocator.allocate(remainders, rest)
                    .forEach((id, units) -> result.merge(id, units, Long::sum));
        }

        long sum = result.values().stream().mapToLong(Long::longValue).sum();
        if (sum != totalUnits) {
            throw new IllegalStateException(
                    "혼합배정 불변식 위반: Σa=%d ≠ N=%d".formatted(sum, totalUnits));
        }
        // 신청량 초과 배정이 없어야 한다
        Map<Long, Long> requestedById = new LinkedHashMap<>();
        inputs.forEach(i -> requestedById.put(i.id(), i.requested()));
        result.forEach((id, units) -> {
            if (units > requestedById.getOrDefault(id, 0L)) {
                throw new IllegalStateException(
                        "신청량을 넘겨 배정했다: id=%d 배정=%d 신청=%d"
                                .formatted(id, units, requestedById.get(id)));
            }
        });
        return sortedById(result);
    }

    /** 출력 순서를 청약ID 기준으로 고정한다 — 같은 입력이면 같은 결과여야 한다. */
    private static Map<Long, Long> sortedById(Map<Long, Long> result) {
        Map<Long, Long> ordered = new LinkedHashMap<>();
        result.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }
}

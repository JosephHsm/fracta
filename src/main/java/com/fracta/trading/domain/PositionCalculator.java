package com.fracta.trading.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 취득원가 재생 — 순수 함수. DB를 모른다.
 *
 * <p>조각을 얻는 경로가 청약과 매매 둘이고 가격이 제각각이라, 남은 수량에 얼마가 묻혀 있는지는
 * <b>시간순으로 재생해야</b> 나온다. <b>이동평균</b>을 쓴다 — 매도할 때 그 시점 평균단가만큼
 * 원가를 덜어낸다. 이걸 안 하면 10주 사서 5주 판 뒤 남은 5주에 10주치 원가가 그대로 붙는다.
 *
 * <p>부동소수점을 쓰지 않는다. 평균단가를 따로 들고 있지 않고 <b>누적 원가 총액</b>만 유지하다가
 * 매도 시점에 {@code 원가 × 매도수량 ÷ 보유수량}으로 덜어낸다. 나눗셈이 한 번뿐이라 반올림
 * 오차가 누적되지 않는다.
 *
 * <p>매수 수수료는 원가에 포함한다 — 실제로 나간 돈이다. 매도 수수료는 실현손익 쪽이라
 * 남은 포지션의 원가에 영향을 주지 않는다.
 */
public final class PositionCalculator {

    /** 취득/처분 한 건. */
    public record Lot(Instant at, Side side, long units, long unitPrice, long fee) {

        public enum Side { ACQUIRE, DISPOSE }

        public static Lot acquire(Instant at, long units, long unitPrice, long fee) {
            return new Lot(at, Side.ACQUIRE, units, unitPrice, fee);
        }

        public static Lot dispose(Instant at, long units) {
            return new Lot(at, Side.DISPOSE, units, 0, 0);
        }
    }

    /**
     * 재생 결과.
     *
     * @param units    재생으로 계산된 보유 수량
     * @param costBasis 그 수량에 묻혀 있는 취득원가 총액 (매수 수수료 포함)
     */
    public record CostBasis(long units, long costBasis) {

        public static final CostBasis EMPTY = new CostBasis(0, 0);
    }

    private PositionCalculator() {
    }

    /** 시간 오름차순으로 재생한다. 입력 순서는 신경 쓰지 않는다 — 여기서 정렬한다. */
    public static CostBasis replay(List<Lot> lots) {
        List<Lot> ordered = new ArrayList<>(lots);
        ordered.sort(Comparator.comparing(Lot::at));

        long units = 0;
        long cost = 0;
        for (Lot lot : ordered) {
            if (lot.units() <= 0) {
                continue;
            }
            if (lot.side() == Lot.Side.ACQUIRE) {
                units = Math.addExact(units, lot.units());
                cost = Math.addExact(cost,
                        Math.addExact(Math.multiplyExact(lot.unitPrice(), lot.units()), lot.fee()));
                continue;
            }
            // 처분 — 보유분을 넘겨 팔 수는 없다. 기록이 어긋나면 남은 만큼만 턴다
            long sold = Math.min(lot.units(), units);
            if (units == 0) {
                continue;
            }
            long removed = sold == units
                    ? cost   // 전량 처분이면 원가를 남김없이 턴다 (절사 잔액이 남지 않게)
                    : java.math.BigInteger.valueOf(cost)
                            .multiply(java.math.BigInteger.valueOf(sold))
                            .divide(java.math.BigInteger.valueOf(units))
                            .longValueExact();
            units -= sold;
            cost -= removed;
        }
        return new CostBasis(units, cost);
    }
}

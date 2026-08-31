package com.fracta.external.broker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import com.fracta.common.money.Money;

/**
 * 시세 → 조각 환산·괴리율 (FSD §9.4) — 순수 함수.
 *
 * <pre>
 * 조각 참조가 = round(원자산 현재가 / 분할비율)
 * 괴리율     = (플랫폼 체결가 - 조각 참조가) / 조각 참조가 × 100
 * </pre>
 *
 * 반올림은 {@link RoundingMode#HALF_UP} 으로 고정한다. 원자산 가격이 0이거나 미조회면
 * 괴리율 계산을 건너뛴다 (0으로 나누기 방지).
 */
public final class PriceConverter {

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int PREMIUM_SCALE = 4;

    private PriceConverter() {
    }

    /** 조각 참조가. 결과가 0원이 되면 최소 1원으로 올린다 (0원 조각은 의미가 없다). */
    public static Money referencePrice(Money underlyingPrice, long splitRatio) {
        if (splitRatio <= 0) {
            throw new IllegalArgumentException("분할비율은 1 이상이어야 한다: " + splitRatio);
        }
        if (underlyingPrice.isZero()) {
            return Money.ZERO;
        }
        long rounded = BigDecimal.valueOf(underlyingPrice.amount())
                .divide(BigDecimal.valueOf(splitRatio), 0, ROUNDING)
                .longValueExact();
        return Money.of(Math.max(1, rounded));
    }

    /**
     * 괴리율(%). 참조가가 0(원자산 미조회·0원)이면 비어 있는 값을 돌려준다 — 호출자가 건너뛴다.
     */
    public static Optional<BigDecimal> premiumRate(Money executedPrice, Money referencePrice) {
        if (referencePrice.isZero()) {
            return Optional.empty();
        }
        BigDecimal reference = referencePrice.toDisplay();
        BigDecimal diff = executedPrice.toDisplay().subtract(reference);
        return Optional.of(diff.multiply(BigDecimal.valueOf(100))
                .divide(reference, PREMIUM_SCALE, ROUNDING));
    }

    /** 원자산 가격과 분할비율로부터 곧바로 괴리율을 구한다. */
    public static Optional<BigDecimal> premiumRate(Money executedPrice, Money underlyingPrice,
                                                   long splitRatio) {
        return premiumRate(executedPrice, referencePrice(underlyingPrice, splitRatio));
    }
}

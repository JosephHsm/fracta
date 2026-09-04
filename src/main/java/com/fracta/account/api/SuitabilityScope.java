package com.fracta.account.api;

/**
 * 부적합 확인 서명이 커버하는 범위 (AC-04).
 *
 * <p>서명은 <b>상품 하나</b>에 대해서만 유효하다. 예전에는 등급만 보고 "그 등급 이하 전부"를
 * 커버해서, 한 번 서명하면 이후 모든 발행 건에 대해 적합성 가드가 사라졌다.
 * 실제 부적합 확인은 거래 건별로 받는다.
 *
 * @param type 청약이면 {@code ISSUANCE}, 유통이면 {@code TOKEN}
 * @param id   발행 건 ID 또는 토큰 심볼
 */
public record SuitabilityScope(Type type, String id) {

    public enum Type { ISSUANCE, TOKEN }

    public SuitabilityScope {
        if (type == null) {
            throw new IllegalArgumentException("서명 범위 유형은 필수다");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("서명 범위 식별자는 필수다");
        }
    }

    public static SuitabilityScope issuance(long issuanceId) {
        return new SuitabilityScope(Type.ISSUANCE, String.valueOf(issuanceId));
    }

    public static SuitabilityScope token(String tokenSymbol) {
        return new SuitabilityScope(Type.TOKEN, tokenSymbol);
    }
}

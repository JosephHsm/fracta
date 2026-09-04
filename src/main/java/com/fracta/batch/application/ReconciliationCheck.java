package com.fracta.batch.application;

import java.math.BigInteger;

/** INV-1~6 한 항목의 검증 결과. 값은 합계 오버플로우를 피하려고 BigInteger로 유지한다. */
public record ReconciliationCheck(
        String invariantCode,
        String tokenSymbol,
        boolean valid,
        BigInteger expected,
        BigInteger actual,
        BigInteger difference,
        Long firstMismatchSeq,
        Long checkedCount,
        String detail) {

    public static final String GLOBAL_SCOPE = "*";

    /**
     * 위반 시 무엇까지 멈출지. <b>폭발 반경이 검증 종류마다 다르다.</b>
     *
     * <p>FSD §8.1은 "위반 시 해당 토큰 SUSPENDED" 한 줄만 뒀는데, 그건 토큰 단위 불변식을
     * 상정하고 쓴 문장이다. INV-3(현금)·INV-6처럼 애초에 토큰 개념이 없는 전역 집계에
     * 그대로 적용하면 <b>상장 전 종목이 정지되고 전 주문이 취소</b>된다. 게다가 전역 집계는
     * 여러 테이블을 훑어 더하는 값이라 항이 하나 빠지거나 스냅샷이 어긋나기만 해도 틀린다 —
     * 정상 상태에서 플랫폼을 멈추는 쪽이 원래 막으려던 훼손보다 큰 사고다.
     */
    public enum Severity {
        /** 해당 토큰만 거래 중단. 그 토큰의 원장이 실제로 어긋났다는 뜻이다. */
        HALT_TOKEN,
        /** 플랫폼 전체 거래 중단. 원장 자체가 훼손된 경우(INV-4)에만 쓴다. */
        HALT_ALL,
        /** 경보만 남기고 상태는 바꾸지 않는다. 중단 판단은 사람이 한다. */
        ALERT
    }

    /** 체인 무결성 — 원장 자체가 깨졌다는 뜻이라 범위가 전역이어도 중단한다. */
    private static final String CHAIN_INTEGRITY = "INV-4";

    /**
     * 이 위반의 심각도.
     *
     * <p>기준은 "코드"가 아니라 <b>범위 + 성격</b>이다. 같은 INV-3도 토큰 잔고 음수면
     * 그 토큰의 문제지만, 현금 잔고 음수는 전역 집계다.
     */
    public Severity severity() {
        if (CHAIN_INTEGRITY.equals(invariantCode)) {
            return Severity.HALT_ALL;
        }
        return GLOBAL_SCOPE.equals(tokenSymbol) ? Severity.ALERT : Severity.HALT_TOKEN;
    }

    public static ReconciliationCheck amounts(String code, String symbol,
                                              BigInteger expected, BigInteger actual,
                                              String detail) {
        return new ReconciliationCheck(code, symbol, expected.equals(actual), expected, actual,
                actual.subtract(expected), null, null, detail);
    }

    public static ReconciliationCheck count(String code, String symbol, long violations,
                                            String detail) {
        BigInteger actual = BigInteger.valueOf(violations);
        return new ReconciliationCheck(code, symbol, violations == 0, BigInteger.ZERO, actual,
                actual, null, null, detail);
    }

    public static ReconciliationCheck informational(String code, String symbol, String detail) {
        return new ReconciliationCheck(code, symbol, true, null, null, null,
                null, null, detail);
    }
}

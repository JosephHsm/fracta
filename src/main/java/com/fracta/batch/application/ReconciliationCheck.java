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

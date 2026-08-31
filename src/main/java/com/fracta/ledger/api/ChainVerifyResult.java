package com.fracta.ledger.api;

/**
 * 해시체인 구간 검증 결과. valid=false면 firstMismatchSeq가 최초 불일치 지점을 가리킨다.
 */
public record ChainVerifyResult(
        boolean valid,
        long fromSeq,
        long toSeq,
        long checkedCount,
        Long firstMismatchSeq,
        String detail
) {

    public static ChainVerifyResult ok(long fromSeq, long toSeq, long checkedCount) {
        return new ChainVerifyResult(true, fromSeq, toSeq, checkedCount, null, null);
    }

    public static ChainVerifyResult mismatch(long fromSeq, long toSeq, long checkedCount,
                                             long mismatchSeq, String detail) {
        return new ChainVerifyResult(false, fromSeq, toSeq, checkedCount, mismatchSeq, detail);
    }
}

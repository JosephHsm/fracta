package com.fracta.common.config;

/**
 * pg_advisory_xact_lock ID 단일 관리처. 새 락은 반드시 여기에 상수로 추가해 ID 충돌을 방지한다.
 */
public final class AdvisoryLockIds {

    /** 원장 해시체인 직렬화 (Phase 2). */
    public static final long LEDGER_CHAIN = 1001L;

    /** 토큰 심볼 연번 발급 직렬화 (Phase 3). 2-키 락의 classId — objId는 자산코드 해시. */
    public static final int TOKEN_SYMBOL_CLASS = 1002;

    private AdvisoryLockIds() {
    }
}

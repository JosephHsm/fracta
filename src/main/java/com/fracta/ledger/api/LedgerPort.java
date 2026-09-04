package com.fracta.ledger.api;

import com.fracta.common.money.Units;

/**
 * 원장 포트 — 시스템의 단일 진실 공급원. 시그니처 변경 금지 (FSD §6.4).
 *
 * <p>매도 잠금분 이전 규칙(Phase 6에서 구현): DvP는 같은 트랜잭션 안에서
 * {@code unlock} 후 {@code transfer}를 수행한다. 잠금분을 직접 이전하는 연산은 두지 않는다.
 */
public interface LedgerPort {

    LedgerTxId issue(String tokenSymbol, OwnerId to, Units units, TxRef ref);

    LedgerTxId transfer(String tokenSymbol, OwnerId from, OwnerId to, Units units, TxRef ref);

    LedgerTxId lock(String tokenSymbol, OwnerId owner, Units units, TxRef ref);

    LedgerTxId unlock(String tokenSymbol, OwnerId owner, Units units, TxRef ref);

    Balance balanceOf(String tokenSymbol, OwnerId owner);

    Units totalIssued(String tokenSymbol);

    ChainVerifyResult verifyChain(long fromSeq, long toSeq);

    InvariantResult verifyInvariant(String tokenSymbol);

    /**
     * 토큰 단위 불변식(INV-1·2·3)을 항목별로 검증한다. 야간 대사 배치가 이걸 부른다.
     *
     * <p>{@link #verifyInvariant}와 <b>같은 계산을 공유</b>한다. 예전에는 배치가 같은 검증을
     * SQL로 따로 구현해 두 벌이 존재했고, 한쪽만 고치면 검증기가 서로 다른 답을 냈다.
     */
    java.util.List<com.fracta.common.invariant.ReconciliationCheck> checkTokenInvariants(
            String tokenSymbol);
}

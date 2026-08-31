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
}

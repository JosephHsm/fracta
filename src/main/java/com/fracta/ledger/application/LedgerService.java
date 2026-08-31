package com.fracta.ledger.application;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.common.money.Units;
import com.fracta.ledger.api.Balance;
import com.fracta.ledger.api.ChainVerifyResult;
import com.fracta.ledger.api.InvariantResult;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.LedgerTxId;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.TxRef;
import com.fracta.ledger.infrastructure.HashChainLedgerAdapter;

/**
 * 원장 유스케이스 — 트랜잭션 경계 (CLAUDE.md: @Transactional은 application 레이어에만).
 * 다른 모듈이 자기 트랜잭션 안에서 호출하면 REQUIRED 전파로 합류한다.
 */
@Service
@Primary
public class LedgerService implements LedgerPort {

    private final HashChainLedgerAdapter adapter;

    public LedgerService(HashChainLedgerAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    @Transactional
    public LedgerTxId issue(String tokenSymbol, OwnerId to, Units units, TxRef ref) {
        return adapter.issue(tokenSymbol, to, units, ref);
    }

    @Override
    @Transactional
    public LedgerTxId transfer(String tokenSymbol, OwnerId from, OwnerId to, Units units, TxRef ref) {
        return adapter.transfer(tokenSymbol, from, to, units, ref);
    }

    @Override
    @Transactional
    public LedgerTxId lock(String tokenSymbol, OwnerId owner, Units units, TxRef ref) {
        return adapter.lock(tokenSymbol, owner, units, ref);
    }

    @Override
    @Transactional
    public LedgerTxId unlock(String tokenSymbol, OwnerId owner, Units units, TxRef ref) {
        return adapter.unlock(tokenSymbol, owner, units, ref);
    }

    @Override
    @Transactional(readOnly = true)
    public Balance balanceOf(String tokenSymbol, OwnerId owner) {
        return adapter.balanceOf(tokenSymbol, owner);
    }

    @Override
    @Transactional(readOnly = true)
    public Units totalIssued(String tokenSymbol) {
        return adapter.totalIssued(tokenSymbol);
    }

    @Override
    @Transactional(readOnly = true)
    public ChainVerifyResult verifyChain(long fromSeq, long toSeq) {
        return adapter.verifyChain(fromSeq, toSeq);
    }

    @Override
    @Transactional(readOnly = true)
    public InvariantResult verifyInvariant(String tokenSymbol) {
        return adapter.verifyInvariant(tokenSymbol);
    }
}

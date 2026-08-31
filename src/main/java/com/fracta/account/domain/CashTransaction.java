package com.fracta.account.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 예치금 입출금 기록 — append-only (INV-6 검증 근거). */
@Entity
@Table(name = "cash_transaction")
public class CashTransaction {

    /** DEPOSIT/WITHDRAW는 외부 입출금, 나머지는 내부 이동 (INV-6에서 구분). */
    public enum Type {
        DEPOSIT, WITHDRAW,
        MARGIN_HOLD, MARGIN_REFUND, SETTLEMENT_CREDIT,
        /** 매매 대금 차감·지급, 수수료 수입 */
        TRADE_DEBIT, TRADE_CREDIT, FEE_INCOME
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_id", nullable = false)
    private long investorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false)
    private Type txType;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected CashTransaction() {
    }

    public CashTransaction(long investorId, Type txType, long amount, long balanceAfter) {
        this.investorId = investorId;
        this.txType = txType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    public Type txType() {
        return txType;
    }

    public long amount() {
        return amount;
    }

    public long balanceAfter() {
        return balanceAfter;
    }
}

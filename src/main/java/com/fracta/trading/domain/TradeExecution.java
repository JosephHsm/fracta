package com.fracta.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 체결 기록. append-only (DB에서 UPDATE/DELETE 권한 회수). */
@Entity
@Table(name = "trade_execution")
public class TradeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_symbol", nullable = false)
    private String tokenSymbol;

    @Column(name = "buy_order_id", nullable = false)
    private long buyOrderId;

    @Column(name = "sell_order_id", nullable = false)
    private long sellOrderId;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private long units;

    @Column(name = "buy_fee", nullable = false)
    private long buyFee;

    @Column(name = "sell_fee", nullable = false)
    private long sellFee;

    /** 원자산 시세를 못 구하면 null — 괴리율 계산을 건너뛴 것이다. */
    @Column(name = "premium_rate")
    private BigDecimal premiumRate;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    protected TradeExecution() {
    }

    public TradeExecution(String tokenSymbol, long buyOrderId, long sellOrderId, long price,
                          long units, long buyFee, long sellFee, BigDecimal premiumRate) {
        this.tokenSymbol = tokenSymbol;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.price = price;
        this.units = units;
        this.buyFee = buyFee;
        this.sellFee = sellFee;
        this.premiumRate = premiumRate;
        this.executedAt = Instant.now();
    }

    public Long id() {
        return id;
    }

    public String tokenSymbol() {
        return tokenSymbol;
    }

    public long buyOrderId() {
        return buyOrderId;
    }

    public long sellOrderId() {
        return sellOrderId;
    }

    public long price() {
        return price;
    }

    public long units() {
        return units;
    }

    public long buyFee() {
        return buyFee;
    }

    public long sellFee() {
        return sellFee;
    }

    public BigDecimal premiumRate() {
        return premiumRate;
    }

    public Instant executedAt() {
        return executedAt;
    }
}

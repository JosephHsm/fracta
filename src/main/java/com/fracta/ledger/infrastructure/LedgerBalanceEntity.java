package com.fracta.ledger.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** ledger_balance 영속 모델. 수량 산술은 어댑터가 Units 값 객체로 수행한다. */
@Entity
@Table(name = "ledger_balance")
public class LedgerBalanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(name = "token_symbol", nullable = false)
    private String tokenSymbol;

    @Column(nullable = false)
    private long units;

    @Column(name = "locked_units", nullable = false)
    private long lockedUnits;

    @Version
    private long version;

    protected LedgerBalanceEntity() {
    }

    public LedgerBalanceEntity(long ownerId, String tokenSymbol) {
        this.ownerId = ownerId;
        this.tokenSymbol = tokenSymbol;
        this.units = 0;
        this.lockedUnits = 0;
    }

    public long ownerId() {
        return ownerId;
    }

    public String tokenSymbol() {
        return tokenSymbol;
    }

    public long units() {
        return units;
    }

    public long lockedUnits() {
        return lockedUnits;
    }

    public void setUnits(long units) {
        this.units = units;
    }

    public void setLockedUnits(long lockedUnits) {
        this.lockedUnits = lockedUnits;
    }
}

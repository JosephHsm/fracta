package com.fracta.subscription.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "subscription_order")
public class SubscriptionOrder {

    public enum Status { PENDING, DEPOSITED, ALLOTTED, PARTIALLY_ALLOTTED, REJECTED, SETTLED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issuance_id", nullable = false)
    private long issuanceId;

    @Column(name = "investor_id", nullable = false)
    private long investorId;

    @Column(name = "requested_units", nullable = false)
    private long requestedUnits;

    @Column(name = "allotted_units")
    private Long allottedUnits;

    @Column(name = "deposit_amount", nullable = false)
    private long depositAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DEPOSITED;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Version
    private long version;

    protected SubscriptionOrder() {
    }

    public SubscriptionOrder(long issuanceId, long investorId, long requestedUnits,
                             long depositAmount, String idempotencyKey) {
        this.issuanceId = issuanceId;
        this.investorId = investorId;
        this.requestedUnits = requestedUnits;
        this.depositAmount = depositAmount;
        this.idempotencyKey = idempotencyKey;
        this.appliedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
    }

    /** 배정 확정 — allotted 0이면 REJECTED(전액 환불), 아니면 SETTLED. */
    public void settle(long allotted) {
        if (allotted < 0 || allotted > requestedUnits) {
            throw new IllegalStateException("배정 수량이 신청 범위를 벗어났다: " + allotted);
        }
        this.allottedUnits = allotted;
        this.status = allotted == 0 ? Status.REJECTED : Status.SETTLED;
    }

    public Long id() {
        return id;
    }

    public long issuanceId() {
        return issuanceId;
    }

    public long investorId() {
        return investorId;
    }

    public long requestedUnits() {
        return requestedUnits;
    }

    public Long allottedUnits() {
        return allottedUnits;
    }

    public long depositAmount() {
        return depositAmount;
    }

    public Status status() {
        return status;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant appliedAt() {
        return appliedAt;
    }
}

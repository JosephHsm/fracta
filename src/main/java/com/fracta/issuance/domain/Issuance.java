package com.fracta.issuance.domain;

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
@Table(name = "issuance")
public class Issuance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private long assetId;

    @Column(name = "token_symbol", nullable = false, unique = true)
    private String tokenSymbol;

    @Column(name = "total_units", nullable = false)
    private long totalUnits;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "remaining_units", nullable = false)
    private long remainingUnits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssuanceStatus status = IssuanceStatus.DRAFT;

    @Column(name = "subscription_start_at", nullable = false)
    private Instant subscriptionStartAt;

    @Column(name = "subscription_end_at", nullable = false)
    private Instant subscriptionEndAt;

    @Column(name = "prospectus_file_key")
    private String prospectusFileKey;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected Issuance() {
    }

    public Issuance(long assetId, String tokenSymbol, long totalUnits, long unitPrice,
                    Instant subscriptionStartAt, Instant subscriptionEndAt) {
        this.assetId = assetId;
        this.tokenSymbol = tokenSymbol;
        this.totalUnits = totalUnits;
        this.unitPrice = unitPrice;
        this.remainingUnits = totalUnits;   // Phase 4 방식 C가 사용
        this.subscriptionStartAt = subscriptionStartAt;
        this.subscriptionEndAt = subscriptionEndAt;
    }

    /** 상태 전이의 단일 검증 지점. 허용되지 않으면 STATE_INVALID_TRANSITION. */
    public void transitionTo(IssuanceStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
    }

    public void attachProspectus(String fileKey) {
        this.prospectusFileKey = fileKey;
    }

    public Long id() {
        return id;
    }

    public long assetId() {
        return assetId;
    }

    public String tokenSymbol() {
        return tokenSymbol;
    }

    public long totalUnits() {
        return totalUnits;
    }

    public long unitPrice() {
        return unitPrice;
    }

    public long remainingUnits() {
        return remainingUnits;
    }

    public IssuanceStatus status() {
        return status;
    }

    public Instant subscriptionStartAt() {
        return subscriptionStartAt;
    }

    public String prospectusFileKey() {
        return prospectusFileKey;
    }
}

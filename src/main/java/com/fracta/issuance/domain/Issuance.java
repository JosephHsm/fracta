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

    /**
     * 배정 방식.
     *
     * <p>{@code HYBRID}는 총량의 일부를 참여자에게 균등 배분한 뒤 나머지를 안분비례한다.
     * 순수 안분({@code PRORATA})은 경쟁률이 높으면 소액 청약자가 0주를 받는데, 조각투자의
     * 존재 이유가 소액 접근성이라 상품 컨셉과 어긋난다.
     */
    public enum AllotmentMethod { FCFS, PRORATA, HYBRID }

    @Enumerated(EnumType.STRING)
    @Column(name = "allotment_method", nullable = false)
    private AllotmentMethod allotmentMethod = AllotmentMethod.FCFS;

    @Column(name = "risk_grade", nullable = false)
    private int riskGrade = 3;

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

    /** HYBRID 에서 균등 배분에 쓸 총량 비율(%). 다른 방식에서는 쓰이지 않는다. */
    @Column(name = "equal_allotment_percent", nullable = false)
    private int equalAllotmentPercent = 50;

    public int equalAllotmentPercent() {
        return equalAllotmentPercent;
    }

    /** 균등 배분 비율 조정. HYBRID 가 아니면 값이 남아 있어도 무시된다. */
    public void adjustEqualAllotmentPercent(int percent) {
        if (percent < 0 || percent > 100) {
            throw IssuanceValidationException.equalAllotmentPercentOutOfRange(percent);
        }
        this.equalAllotmentPercent = percent;
    }

    public void configureAllotment(AllotmentMethod method, int riskGrade) {
        this.allotmentMethod = method;
        this.riskGrade = riskGrade;
    }

    /** 잔여 수량 감소 — 반드시 락(비관적/분산) 하에서 호출한다. */
    public void decrementRemaining(long units) {
        if (remainingUnits < units) {
            throw new com.fracta.common.money.InsufficientUnitsException(remainingUnits, units);
        }
        this.remainingUnits -= units;
    }

    public void incrementRemaining(long units) {
        if (remainingUnits + units > totalUnits) {
            throw new IllegalStateException("잔여 수량이 총량을 초과할 수 없다");
        }
        this.remainingUnits += units;
    }

    /** PRORATA 배정 확정 후 잔여 수량 확정. */
    public void settleRemaining(long soldUnits) {
        if (soldUnits < 0 || soldUnits > totalUnits) {
            throw new IllegalStateException("판매 수량이 총량 범위를 벗어났다: " + soldUnits);
        }
        this.remainingUnits = totalUnits - soldUnits;
    }

    public AllotmentMethod allotmentMethod() {
        return allotmentMethod;
    }

    public int riskGrade() {
        return riskGrade;
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

    public Instant subscriptionEndAt() {
        return subscriptionEndAt;
    }

    public String prospectusFileKey() {
        return prospectusFileKey;
    }
}

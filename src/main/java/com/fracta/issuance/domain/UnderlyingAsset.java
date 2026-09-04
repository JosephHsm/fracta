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

@Entity
@Table(name = "underlying_asset")
public class UnderlyingAsset {

    public enum AssetType { REIT, ETF, REAL_ESTATE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false)
    private AssetType assetType;

    @Column(name = "asset_code", nullable = false, unique = true, length = 4)
    private String assetCode;

    @Column(name = "broker_ticker")
    private String brokerTicker;

    @Column(name = "split_ratio", nullable = false)
    private long splitRatio;

    private String description;

    @Column(name = "issuer_id", nullable = false)
    private long issuerId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /**
     * 괴리율 임계치 재정의. null 이면 설정 기본값을 쓴다.
     *
     * <p>REIT·ETF·부동산의 정상 괴리 범위가 같을 리 없다. 자동 거래중단은 되돌리는 비용이
     * 큰 조치라 임계치를 한 값으로 고정해 두면 오작동이든 미작동이든 전부 그 값 탓이 된다.
     */
    @Column(name = "premium_warn_percent")
    private java.math.BigDecimal premiumWarnPercent;

    @Column(name = "premium_suspend_percent")
    private java.math.BigDecimal premiumSuspendPercent;

    protected UnderlyingAsset() {
    }

    public UnderlyingAsset(String name, AssetType assetType, String assetCode, String brokerTicker,
                           long splitRatio, String description, long issuerId) {
        this.name = name;
        this.assetType = assetType;
        this.assetCode = assetCode;
        this.brokerTicker = brokerTicker;
        this.splitRatio = splitRatio;
        this.description = description;
        this.issuerId = issuerId;
    }

    public String name() {
        return name;
    }

    public AssetType assetType() {
        return assetType;
    }

    public String description() {
        return description;
    }

    public Long id() {
        return id;
    }

    public String assetCode() {
        return assetCode;
    }

    public String brokerTicker() {
        return brokerTicker;
    }

    public long splitRatio() {
        return splitRatio;
    }

    public java.math.BigDecimal premiumWarnPercent() {
        return premiumWarnPercent;
    }

    public java.math.BigDecimal premiumSuspendPercent() {
        return premiumSuspendPercent;
    }

    /** 자산 유형에 맞춰 괴리율 임계치를 조정한다. null 을 넣으면 설정 기본값으로 되돌린다. */
    public void adjustPremiumThresholds(java.math.BigDecimal warnPercent,
                                        java.math.BigDecimal suspendPercent) {
        if (warnPercent != null && suspendPercent != null
                && warnPercent.compareTo(suspendPercent) > 0) {
            throw new IllegalArgumentException("경고 임계치가 중단 임계치보다 클 수 없다");
        }
        this.premiumWarnPercent = warnPercent;
        this.premiumSuspendPercent = suspendPercent;
    }

    public long issuerId() {
        return issuerId;
    }
}

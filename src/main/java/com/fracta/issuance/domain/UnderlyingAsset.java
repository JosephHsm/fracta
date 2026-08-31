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

    public long issuerId() {
        return issuerId;
    }
}

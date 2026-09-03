package com.fracta.external.broker.instrument;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 종목마스터 한 줄. 검색용 참조 데이터이며 도메인 상태가 아니다. */
@Entity
@Table(name = "instrument_master")
public class InstrumentMaster {

    @Id
    private String code;

    @Column(nullable = false)
    private String market;

    @Column(name = "kor_name", nullable = false)
    private String korName;

    @Column(name = "eng_name")
    private String engName;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_kind", nullable = false)
    private AssetKind assetKind;

    @Column(name = "prev_close")
    private Long prevClose;

    @Column(name = "market_cap")
    private Long marketCap;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected InstrumentMaster() {
    }

    public InstrumentMaster(InstrumentMasterParser.Row row) {
        this.code = row.code();
        this.market = row.market();
        this.korName = row.korName();
        this.engName = row.engName();
        this.assetKind = row.kind();
        this.prevClose = row.prevClose();
        this.marketCap = row.marketCap();
        this.updatedAt = Instant.now();
    }

    public String code() {
        return code;
    }

    public String korName() {
        return korName;
    }

    public String engName() {
        return engName;
    }

    public String market() {
        return market;
    }

    public AssetKind assetKind() {
        return assetKind;
    }

    public Long prevClose() {
        return prevClose;
    }

    public Long marketCap() {
        return marketCap;
    }
}

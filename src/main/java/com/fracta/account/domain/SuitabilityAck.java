package com.fracta.account.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 부적합 확인 서명 이력 — append-only. 서명한 등급 이하의 상품을 커버한다. */
@Entity
@Table(name = "suitability_ack")
public class SuitabilityAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_id", nullable = false)
    private long investorId;

    @Column(name = "product_grade", nullable = false)
    private int productGrade;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected SuitabilityAck() {
    }

    public SuitabilityAck(long investorId, int productGrade) {
        this.investorId = investorId;
        this.productGrade = productGrade;
    }

    public int productGrade() {
        return productGrade;
    }
}

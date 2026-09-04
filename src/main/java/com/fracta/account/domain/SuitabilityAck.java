package com.fracta.account.domain;

import java.time.Instant;

import com.fracta.account.api.SuitabilityScope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 부적합 확인 서명 이력 — append-only.
 *
 * <p>서명은 <b>상품 하나</b>에만, <b>정해진 기간 동안만</b> 유효하다. 예전에는 등급만 보고
 * 그 이하 전 상품을 영구히 커버해서, 한 번 서명하면 적합성 가드가 사라졌다.
 *
 * <p>재진단 무효화는 이 테이블을 고쳐서 하지 않는다(UPDATE 권한이 없다). 대신 조회 시점에
 * "현재 성향 진단보다 나중에 서명됐는가"를 함께 본다 — 새 진단이 나오면 이전 서명은
 * 한꺼번에 효력을 잃는다.
 */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    private SuitabilityScope.Type scopeType;

    @Column(name = "scope_id", nullable = false)
    private String scopeId;

    /** 서명 만료 시각. 성향 진단 만료와 같은 시점으로 맞춘다. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected SuitabilityAck() {
    }

    public SuitabilityAck(long investorId, int productGrade, SuitabilityScope scope,
                          Instant expiresAt) {
        this.investorId = investorId;
        this.productGrade = productGrade;
        this.scopeType = scope.type();
        this.scopeId = scope.id();
        this.expiresAt = expiresAt;
    }

    public int productGrade() {
        return productGrade;
    }

    public SuitabilityScope scope() {
        return new SuitabilityScope(scopeType, scopeId);
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

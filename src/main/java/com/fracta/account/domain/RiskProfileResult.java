package com.fracta.account.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "risk_profile_result")
public class RiskProfileResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_id", nullable = false)
    private long investorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String answers;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private int grade;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RiskProfileResult() {
    }

    public RiskProfileResult(long investorId, String answersJson, int score, int grade, Instant expiresAt) {
        this.investorId = investorId;
        this.answers = answersJson;
        this.score = score;
        this.grade = grade;
        this.expiresAt = expiresAt;
    }

    public int score() {
        return score;
    }

    public int grade() {
        return grade;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}

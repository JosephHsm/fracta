package com.fracta.account.domain;

import java.time.Instant;

import com.fracta.account.api.KycStatus;
import com.fracta.account.api.RiskGrade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 투자자.
 *
 * <p>{@code @DynamicUpdate}가 반드시 필요하다. 잔액은 {@code InvestorRepository}의 원자적
 * UPDATE로만 움직이는데, 이 엔티티를 읽어 다른 필드(예: KYC 상태)를 고치면 JPA는 기본적으로
 * <b>행 전체</b>를 쓴다. 그러면 로드 시점의 낡은 {@code cash_balance}가 그 사이 확정된 매매 대금을
 * 덮어써 돈이 사라진다(실제로 발생했다). 변경된 컬럼만 쓰게 해서 이 lost update를 막는다.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "investor")
public class Investor {

    public enum Role { INVESTOR, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "ci_hash", nullable = false)
    private String ciHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.INVESTOR;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    private KycStatus kycStatus = KycStatus.PENDING;

    @Column(name = "risk_grade")
    private Integer riskGrade;

    @Column(name = "risk_grade_expires_at")
    private Instant riskGradeExpiresAt;

    @Column(name = "cash_balance", nullable = false)
    private long cashBalance;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected Investor() {
    }

    public Investor(String name, String email, String passwordHash, String ciHash) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.ciHash = ciHash;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Role role() {
        return role;
    }

    public void grantAdmin() {
        this.role = Role.ADMIN;
    }

    public KycStatus kycStatus() {
        return kycStatus;
    }

    public void verifyKyc() {
        this.kycStatus = KycStatus.VERIFIED;
    }

    public long cashBalance() {
        return cashBalance;
    }

    /** 현재 유효한 성향등급. 진단 이력이 없거나 만료면 null. */
    public RiskGrade validRiskGrade(Instant now) {
        if (riskGrade == null || riskGradeExpiresAt == null || now.isAfter(riskGradeExpiresAt)) {
            return null;
        }
        return RiskGrade.fromLevel(riskGrade);
    }

    public void applyRiskProfile(RiskGrade grade, Instant expiresAt) {
        this.riskGrade = grade.level();
        this.riskGradeExpiresAt = expiresAt;
    }
}

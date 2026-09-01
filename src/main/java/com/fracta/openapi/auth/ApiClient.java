package com.fracta.openapi.auth;

import java.time.Instant;

import com.fracta.openapi.ApiEnv;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 오픈 API 클라이언트. client_secret 은 해시만 보관한다 (FSD §13.2). */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "api_client")
public class ApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false)
    private String clientSecretHash;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_investor_id", nullable = false)
    private long ownerInvestorId;

    @Column(nullable = false)
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApiEnv env;

    @Column(name = "rate_limit_per_sec", nullable = false)
    private int rateLimitPerSec;

    @Column(name = "rate_limit_per_day", nullable = false)
    private int rateLimitPerDay;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected ApiClient() {
    }

    public ApiClient(String clientId, String clientSecretHash, String name, long ownerInvestorId,
                     String scopes, ApiEnv env, int rateLimitPerSec, int rateLimitPerDay) {
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.name = name;
        this.ownerInvestorId = ownerInvestorId;
        this.scopes = scopes;
        this.env = env;
        this.rateLimitPerSec = rateLimitPerSec;
        this.rateLimitPerDay = rateLimitPerDay;
    }

    /** 재발급 — 기존 secret 은 이 시점부터 즉시 무효가 된다. */
    public void rotateSecret(String newSecretHash) {
        this.clientSecretHash = newSecretHash;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long id() {
        return id;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecretHash() {
        return clientSecretHash;
    }

    public String name() {
        return name;
    }

    public long ownerInvestorId() {
        return ownerInvestorId;
    }

    public String scopes() {
        return scopes;
    }

    public ApiEnv env() {
        return env;
    }

    public int rateLimitPerSec() {
        return rateLimitPerSec;
    }

    public int rateLimitPerDay() {
        return rateLimitPerDay;
    }

    public boolean active() {
        return active;
    }
}

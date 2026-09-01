package com.fracta.openapi.log;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_call_log")
public class ApiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false)
    private String method;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    /** 본문 자체는 저장하지 않는다. 대조용 해시만. */
    @Column(name = "body_hash")
    private String bodyHash;

    @Column(name = "called_at", nullable = false, updatable = false, insertable = false)
    private Instant calledAt;

    protected ApiCallLog() {
    }

    public ApiCallLog(String clientId, String endpoint, String method, int statusCode,
                      long latencyMs, String idempotencyKey, String bodyHash) {
        this.clientId = clientId;
        this.endpoint = endpoint;
        this.method = method;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.idempotencyKey = idempotencyKey;
        this.bodyHash = bodyHash;
    }

    public Long id() {
        return id;
    }

    public String clientId() {
        return clientId;
    }

    public String endpoint() {
        return endpoint;
    }

    public int statusCode() {
        return statusCode;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}

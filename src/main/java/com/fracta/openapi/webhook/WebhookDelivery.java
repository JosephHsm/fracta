package com.fracta.openapi.webhook;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 웹훅 발송 시도 기록. 5회 재시도 후에도 실패하면 DEAD(=DLQ)로 남는다. */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    public enum Status { PENDING, DELIVERED, DEAD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "endpoint_id", nullable = false)
    private long endpointId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookDelivery() {
    }

    public WebhookDelivery(long endpointId, String eventType, String payload) {
        this.endpointId = endpointId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public void recordAttempt(String error) {
        this.attempts++;
        this.lastError = error == null ? null
                : error.substring(0, Math.min(500, error.length()));
    }

    public void markDelivered() {
        this.status = Status.DELIVERED;
        this.deliveredAt = Instant.now();
    }

    /** 재시도 소진 — DLQ. 포털에서 수동 재발송 대상이 된다. */
    public void markDead() {
        this.status = Status.DEAD;
    }

    /** DLQ 수동 재발송 — 새 시도 횟수로 다시 시작한다. */
    public void resetForRetry() {
        if (status != Status.DEAD) {
            throw new IllegalStateException("DEAD 발송 건만 재발송할 수 있다: " + status);
        }
        this.attempts = 0;
        this.status = Status.PENDING;
        this.lastError = null;
        this.deliveredAt = null;
    }

    public Long id() {
        return id;
    }

    public long endpointId() {
        return endpointId;
    }

    public String eventType() {
        return eventType;
    }

    public String payload() {
        return payload;
    }

    public int attempts() {
        return attempts;
    }

    public Status status() {
        return status;
    }

    public String lastError() {
        return lastError;
    }
}

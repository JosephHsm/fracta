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

    /**
     * 다음 시도 시각. 워커는 이 시각이 지난 PENDING 건만 다시 집는다.
     *
     * <p>재시도를 워커 스레드에서 {@code sleep} 으로 기다리면 그동안 다른 엔드포인트의
     * 발송이 전부 밀린다. 대기를 시각으로 적어 두고 워커는 바로 다음 건으로 넘어간다.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

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

    /** 다음 시도를 예약한다. 그때까지 워커는 이 건을 집지 않는다. */
    public void scheduleRetryAt(Instant at) {
        this.nextAttemptAt = at;
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
        this.nextAttemptAt = Instant.now();
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

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }
}

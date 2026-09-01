package com.fracta.openapi.webhook;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "webhook_endpoint")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "owner_investor_id", nullable = false)
    private long ownerInvestorId;

    @Column(nullable = false)
    private String url;

    /** HMAC 서명에 원문이 필요해 값을 보관한다. 조회 API로 노출하지 않는다. */
    @Column(nullable = false)
    private String secret;

    @Column(nullable = false)
    private String events;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected WebhookEndpoint() {
    }

    public WebhookEndpoint(String clientId, long ownerInvestorId, String url, String secret,
                           Set<WebhookEvent> events) {
        this.clientId = clientId;
        this.ownerInvestorId = ownerInvestorId;
        this.url = url;
        this.secret = secret;
        this.events = events.stream().map(WebhookEvent::value).collect(Collectors.joining(","));
    }

    public boolean subscribes(WebhookEvent event) {
        return Arrays.asList(events.split(",")).contains(event.value());
    }

    public Long id() {
        return id;
    }

    public String clientId() {
        return clientId;
    }

    public long ownerInvestorId() {
        return ownerInvestorId;
    }

    public String url() {
        return url;
    }

    public String secret() {
        return secret;
    }

    public String events() {
        return events;
    }

    public boolean active() {
        return active;
    }
}

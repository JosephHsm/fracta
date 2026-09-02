package com.fracta.openapi.webhook;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** 중첩 인터페이스는 Spring Data 스캔 대상이 아니라 최상위로 둔다. */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    List<WebhookEndpoint> findByActiveTrue();

    List<WebhookEndpoint> findByClientIdAndActiveTrue(String clientId);

    List<WebhookEndpoint> findByClientIdOrderByIdDesc(String clientId);
}

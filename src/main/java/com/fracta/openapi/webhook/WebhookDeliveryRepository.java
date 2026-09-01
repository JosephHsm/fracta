package com.fracta.openapi.webhook;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    List<WebhookDelivery> findByStatus(WebhookDelivery.Status status);

    List<WebhookDelivery> findByEndpointIdOrderByIdDesc(long endpointId);
}

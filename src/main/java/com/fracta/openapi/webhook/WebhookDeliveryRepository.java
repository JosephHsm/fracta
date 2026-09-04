package com.fracta.openapi.webhook;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    List<WebhookDelivery> findByStatus(WebhookDelivery.Status status);

    List<WebhookDelivery> findByEndpointIdOrderByIdDesc(long endpointId);

    /**
     * 재시도 시각이 도래한 발송 건. 워커가 매 주기 이만큼만 집어 한 번씩 시도한다.
     *
     * <p>한 번에 가져오는 수를 제한한다 — 한 틱이 밀린 건을 전부 처리하려 들면
     * 예전의 인라인 재시도와 같은 이유로 워커가 오래 잡힌다.
     */
    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            WebhookDelivery.Status status, Instant dueAt, Limit limit);
}

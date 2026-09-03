package com.fracta.issuance.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.infrastructure.IssuanceRepository;

/**
 * IS-06: subscription_start_at 도달한 APPROVED 발행 건을 SUBSCRIBING으로 전환한다.
 *
 * <p>스캔은 전역이다 — 조건에 맞는 모든 발행 건의 버전을 올린다. 그래서 통합 테스트에서는
 * 자동 실행을 끈다(`application-test.yml`). 켜두면 다른 테스트가 쓰던 Issuance 행을
 * 스케줄러가 먼저 건드려 낙관적 락 충돌이 난다. 실제로 전체 스위트에서만 간헐적으로
 * 터졌다(SubscriptionFlowIntegrationTest, Issuance#144).
 * 스캔 로직 자체는 IssuanceLifecycleIntegrationTest가 이 메서드를 직접 호출해 검증한다.
 */
@ConditionalOnProperty(name = "issuance.subscription-open-scan.enabled",
        havingValue = "true", matchIfMissing = true)
@Component
public class SubscriptionOpenScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionOpenScheduler.class);

    private final IssuanceRepository issuances;
    private final IssuanceService issuanceService;

    public SubscriptionOpenScheduler(IssuanceRepository issuances, IssuanceService issuanceService) {
        this.issuances = issuances;
        this.issuanceService = issuanceService;
    }

    @Scheduled(fixedDelayString = "${issuance.subscription-open-scan-delay:5000}")
    public void openDueSubscriptions() {
        for (var issuance : issuances.findByStatusAndSubscriptionStartAtLessThanEqual(
                IssuanceStatus.APPROVED, Instant.now())) {
            try {
                issuanceService.openSubscription(issuance.id());
                log.info("청약 개시: issuanceId={} symbol={}", issuance.id(), issuance.tokenSymbol());
            } catch (Exception e) {
                log.error("청약 개시 실패: issuanceId={}", issuance.id(), e);
            }
        }
    }
}

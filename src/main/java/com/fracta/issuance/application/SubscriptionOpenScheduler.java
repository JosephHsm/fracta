package com.fracta.issuance.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.infrastructure.IssuanceRepository;

/** IS-06: subscription_start_at 도달한 APPROVED 발행 건을 SUBSCRIBING으로 전환한다. */
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

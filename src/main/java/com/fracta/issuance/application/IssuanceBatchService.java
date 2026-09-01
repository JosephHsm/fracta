package com.fracta.issuance.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.audit.api.Auditable;
import com.fracta.issuance.api.IssuanceBatchPort;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.infrastructure.IssuanceRepository;

/** 청약 종료 시각 기반 배정 시작 — Phase 9 배치용 애플리케이션 경계. */
@Service
public class IssuanceBatchService implements IssuanceBatchPort {

    private final IssuanceRepository issuances;

    public IssuanceBatchService(IssuanceRepository issuances) {
        this.issuances = issuances;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findDueForAllotment(Instant now) {
        return issuances.findByStatusAndSubscriptionEndAtLessThanEqual(
                        IssuanceStatus.SUBSCRIBING, now).stream()
                .map(Issuance::id)
                .toList();
    }

    @Override
    @Transactional
    @Auditable(action = "BATCH_ALLOTMENT_START", targetType = "ISSUANCE", targetId = "#p0")
    public void startAllotment(long issuanceId, Instant now) {
        Issuance issuance = issuances.findByIdForUpdate(issuanceId)
                .orElseThrow(() -> new IllegalArgumentException("발행 건이 없다: " + issuanceId));
        if (issuance.status() != IssuanceStatus.SUBSCRIBING) {
            throw new IllegalStateException(
                    "배정 시작 대상이 SUBSCRIBING이 아니다: " + issuance.status());
        }
        if (now.isBefore(issuance.subscriptionEndAt())) {
            throw new IllegalStateException(
                    "청약 종료 시각 전에는 배정을 시작할 수 없다: " + issuance.subscriptionEndAt());
        }
        issuance.transitionTo(IssuanceStatus.ALLOTTING);
    }
}

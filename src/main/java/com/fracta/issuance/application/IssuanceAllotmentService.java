package com.fracta.issuance.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import jakarta.persistence.EntityManager;

import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.issuance.api.IssuanceInfo;
import com.fracta.issuance.api.NotSubscribingException;
import com.fracta.issuance.api.TokenListedEvent;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.infrastructure.IssuanceRepository;

/** IssuanceAllotmentPort 구현 — 잔여 수량 예약·복구·배정 확정 반영. */
@Service
public class IssuanceAllotmentService implements IssuanceAllotmentPort {

    private final IssuanceRepository issuances;
    private final com.fracta.issuance.infrastructure.UnderlyingAssetRepository assets;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    public IssuanceAllotmentService(IssuanceRepository issuances,
                                    com.fracta.issuance.infrastructure.UnderlyingAssetRepository assets,
                                    EntityManager entityManager,
                                    ApplicationEventPublisher events) {
        this.issuances = issuances;
        this.assets = assets;
        this.entityManager = entityManager;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public long remainingUnits(long issuanceId) {
        Long remaining = issuances.findRemainingUnits(issuanceId);
        if (remaining == null) {
            throw new IllegalArgumentException("발행 건이 없다: " + issuanceId);
        }
        return remaining;
    }

    @Override
    @Transactional(readOnly = true)
    public IssuanceInfo info(long issuanceId) {
        return toInfo(load(issuanceId));
    }

    @Override
    @Transactional
    public boolean atomicReserve(long issuanceId, long units) {
        return issuances.atomicReserve(issuanceId, units) == 1;
    }

    @Override
    @Transactional
    public void reserveWithPessimisticLock(long issuanceId, long units) {
        // 호출자가 앞서 info()로 같은 행을 적재해 뒀다면 컨텍스트의 사본은 낡았고,
        // 잠금 조회는 결과 대신 그 사본을 돌려준다 → stale read + @Version 충돌.
        // 사본을 떼어낸 뒤 SELECT ... FOR UPDATE 결과로 새로 적재해야 한다.
        detachCached(issuanceId);
        Issuance issuance = issuances.findByIdForUpdate(issuanceId)
                .orElseThrow(() -> new IllegalArgumentException("발행 건이 없다: " + issuanceId));
        requireSubscribing(issuance);
        issuance.decrementRemaining(units);
    }

    @Override
    @Transactional
    public void reserveGuardedExternally(long issuanceId, long units) {
        // 분산락이 직렬화를 보장하지만 캐시된 사본은 직전 커밋을 못 본다 → 떼어내고 새로 적재
        detachCached(issuanceId);
        Issuance issuance = load(issuanceId);
        requireSubscribing(issuance);
        issuance.decrementRemaining(units);
    }

    @Override
    @Transactional
    public void release(long issuanceId, long units) {
        if (issuances.atomicRelease(issuanceId, units) != 1) {
            Issuance issuance = load(issuanceId);
            throw new NotSubscribingException(issuanceId, issuance.status().name());
        }
    }

    @Override
    @Transactional
    public void settleAndList(long issuanceId, long soldUnits) {
        Issuance issuance = load(issuanceId);
        issuance.settleRemaining(soldUnits);
        issuance.transitionTo(IssuanceStatus.LISTED);
        events.publishEvent(new TokenListedEvent(issuance.id(), issuance.tokenSymbol(),
                issuance.totalUnits(), issuance.unitPrice()));
    }

    /** 영속성 컨텍스트에 남아 있는 사본을 떼어낸다 (없으면 아무 일도 하지 않는다). */
    private void detachCached(long issuanceId) {
        Issuance cached = entityManager.find(Issuance.class, issuanceId);
        if (cached != null) {
            entityManager.detach(cached);
        }
    }

    private void requireSubscribing(Issuance issuance) {
        if (issuance.status() != IssuanceStatus.SUBSCRIBING) {
            throw new NotSubscribingException(issuance.id(), issuance.status().name());
        }
    }

    private Issuance load(long issuanceId) {
        return issuances.findById(issuanceId)
                .orElseThrow(() -> new IllegalArgumentException("발행 건이 없다: " + issuanceId));
    }

    private IssuanceInfo toInfo(Issuance i) {
        return new IssuanceInfo(i.id(), i.tokenSymbol(), i.totalUnits(), i.remainingUnits(),
                i.unitPrice(), i.status().name(), i.allotmentMethod().name(), i.riskGrade(),
                i.subscriptionStartAt(), i.subscriptionEndAt(), assets.findIssuerId(i.assetId()));
    }
}

package com.fracta.subscription.application;

import org.springframework.stereotype.Component;

import com.fracta.issuance.api.IssuanceAllotmentPort;

/** 방식 A — 비관적 락. 발행 건 행을 SELECT ... FOR UPDATE로 잠근 뒤 검증·감소한다. */
@Component
public class PessimisticLockStrategy implements AllotmentStrategy {

    private final IssuanceAllotmentPort issuances;

    public PessimisticLockStrategy(IssuanceAllotmentPort issuances) {
        this.issuances = issuances;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public void reserve(long issuanceId, long units) {
        issuances.reserveWithPessimisticLock(issuanceId, units);
    }

    @Override
    public void release(long issuanceId, long units) {
        issuances.release(issuanceId, units);
    }
}

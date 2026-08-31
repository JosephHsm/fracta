package com.fracta.subscription.application;

import org.springframework.stereotype.Component;

import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.issuance.api.IssuanceInfo;
import com.fracta.issuance.api.NotSubscribingException;

/**
 * 방식 C — DB 원자적 감소 (기본값).
 * {@code UPDATE ... WHERE status='SUBSCRIBING' AND remaining_units >= :req}
 * 한 문장으로 검증·감소가 원자적이라 별도 락이 필요 없다.
 */
@Component
public class AtomicDecrementStrategy implements AllotmentStrategy {

    private final IssuanceAllotmentPort issuances;

    public AtomicDecrementStrategy(IssuanceAllotmentPort issuances) {
        this.issuances = issuances;
    }

    @Override
    public String name() {
        return "atomic";
    }

    @Override
    public void reserve(long issuanceId, long units) {
        if (!issuances.atomicReserve(issuanceId, units)) {
            // 0행 갱신 — 원인 구분: 상태 위반 vs 수량 부족
            IssuanceInfo info = issuances.info(issuanceId);
            if (!info.subscribing()) {
                throw new NotSubscribingException(issuanceId, info.status());
            }
            throw new InsufficientUnitsException(issuances.remainingUnits(issuanceId), units);
        }
    }

    @Override
    public void release(long issuanceId, long units) {
        issuances.release(issuanceId, units);
    }
}

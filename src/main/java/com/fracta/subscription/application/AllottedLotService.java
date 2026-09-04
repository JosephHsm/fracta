package com.fracta.subscription.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.issuance.api.IssuanceAllotmentPort;
import com.fracta.subscription.api.AllottedLotPort;
import com.fracta.subscription.domain.SubscriptionOrder;
import com.fracta.subscription.infrastructure.SubscriptionOrderRepository;

/**
 * {@link AllottedLotPort} 구현 — 배정 완료된 청약을 취득 원가 정보로 바꿔 준다.
 *
 * <p>배정 단가는 발행 단가다. 청약은 전원이 같은 가격에 받으므로 조각당 원가가 곧 그 값이다.
 */
@Service
public class AllottedLotService implements AllottedLotPort {

    private final SubscriptionOrderRepository orders;
    private final IssuanceAllotmentPort issuances;

    public AllottedLotService(SubscriptionOrderRepository orders, IssuanceAllotmentPort issuances) {
        this.orders = orders;
        this.issuances = issuances;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AllottedLot> lotsOf(long investorId) {
        return orders.findByInvestorIdOrderByIdDesc(investorId).stream()
                .filter(order -> order.status() == SubscriptionOrder.Status.SETTLED)
                .filter(order -> order.allottedUnits() != null && order.allottedUnits() > 0)
                .map(this::toLot)
                // 이동평균 재생은 시간 오름차순이어야 한다
                .sorted(java.util.Comparator.comparing(AllottedLot::allottedAt))
                .toList();
    }

    private AllottedLot toLot(SubscriptionOrder order) {
        var info = issuances.info(order.issuanceId());
        return new AllottedLot(info.tokenSymbol(), order.allottedUnits(), info.unitPrice(),
                order.appliedAt());
    }
}

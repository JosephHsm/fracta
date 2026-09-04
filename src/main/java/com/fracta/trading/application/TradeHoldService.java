package com.fracta.trading.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.trading.api.TradeHoldPort;
import com.fracta.trading.infrastructure.TradeOrderRepository;

/** {@link TradeHoldPort} 구현 — 미환급 매수 대금 홀드 합계. */
@Service
public class TradeHoldService implements TradeHoldPort {

    private final TradeOrderRepository orders;

    public TradeHoldService(TradeOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public long outstandingHeldAmount() {
        return orders.sumHeldAmount();
    }
}

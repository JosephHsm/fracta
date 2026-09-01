package com.fracta.trading.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fracta.issuance.api.TokenSuspendedEvent;

/** 모든 거래 중단 원인(괴리율·배치 위반)이 Phase 6의 미체결 주문 정리 규칙을 공유한다. */
@Component
public class TokenSuspendedOrderCleanup {

    private final TradingService trading;

    public TokenSuspendedOrderCleanup(TradingService trading) {
        this.trading = trading;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSuspended(TokenSuspendedEvent event) {
        trading.cancelAllOpenOrders(event.tokenSymbol(), event.reason());
    }
}

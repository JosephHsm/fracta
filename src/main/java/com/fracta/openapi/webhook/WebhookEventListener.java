package com.fracta.openapi.webhook;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fracta.issuance.api.TokenListedEvent;
import com.fracta.issuance.api.TokenSuspendedEvent;
import com.fracta.subscription.api.SubscriptionAllottedEvent;
import com.fracta.trading.api.TradeEvents;

/**
 * 도메인 이벤트 → 웹훅 발송 (FSD §7.4의 6개 이벤트).
 *
 * <p>커밋 후에만 내보낸다. 롤백된 체결을 외부에 알리면 되돌릴 수 없다.
 * 실제 HTTP 전송은 {@link WebhookDispatcher}가 큐에 넣은 뒤 워커가 맡는다 —
 * 여기서 동기 전송하면 수신 측 지연이 주문 API를 막는다.
 */
@Component
public class WebhookEventListener {

    private final WebhookDispatcher dispatcher;

    public WebhookEventListener(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderFilled(TradeEvents.OrderFilled event) {
        dispatcher.publish(WebhookEvent.ORDER_FILLED, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPartiallyFilled(TradeEvents.OrderPartiallyFilled event) {
        dispatcher.publish(WebhookEvent.ORDER_PARTIALLY_FILLED, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCancelled(TradeEvents.OrderCancelled event) {
        dispatcher.publish(WebhookEvent.ORDER_CANCELLED, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSubscriptionAllotted(SubscriptionAllottedEvent event) {
        dispatcher.publish(WebhookEvent.SUBSCRIPTION_ALLOTTED, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTokenListed(TokenListedEvent event) {
        dispatcher.publish(WebhookEvent.TOKEN_LISTED, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTokenSuspended(TokenSuspendedEvent event) {
        dispatcher.publish(WebhookEvent.TOKEN_SUSPENDED, event);
    }
}

package com.fracta.openapi;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.issuance.api.TokenListedEvent;
import com.fracta.issuance.api.TokenSuspendedEvent;
import com.fracta.openapi.webhook.WebhookDispatcher;
import com.fracta.openapi.webhook.WebhookEvent;
import com.fracta.openapi.webhook.WebhookEventListener;
import com.fracta.subscription.api.SubscriptionAllottedEvent;
import com.fracta.trading.api.TradeEvents;

/** FSD §7.4의 도메인 이벤트 6종이 빠짐없이 웹훅 이벤트로 변환되는지 검증한다. */
class WebhookEventListenerTest {

    @Test
    @DisplayName("6개 도메인 이벤트 전부 대응 웹훅으로 발행")
    void publishesAllSixEvents() {
        WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
        WebhookEventListener listener = new WebhookEventListener(dispatcher);

        var filled = new TradeEvents.OrderFilled(1, "FR-T-001", 2, 3, 100);
        var partial = new TradeEvents.OrderPartiallyFilled(1, "FR-T-001", 2, 1, 2, 100);
        var cancelled = new TradeEvents.OrderCancelled(1, "FR-T-001", 2, 2);
        var allotted = new SubscriptionAllottedEvent(1, 2, "FR-T-001", 3, 100);
        var listed = new TokenListedEvent(1, "FR-T-001", 100, 1_000);
        var suspended = new TokenSuspendedEvent("FR-T-001", "괴리율 초과");

        listener.onOrderFilled(filled);
        listener.onOrderPartiallyFilled(partial);
        listener.onOrderCancelled(cancelled);
        listener.onSubscriptionAllotted(allotted);
        listener.onTokenListed(listed);
        listener.onTokenSuspended(suspended);

        verify(dispatcher).publish(WebhookEvent.ORDER_FILLED, filled);
        verify(dispatcher).publish(WebhookEvent.ORDER_PARTIALLY_FILLED, partial);
        verify(dispatcher).publish(WebhookEvent.ORDER_CANCELLED, cancelled);
        verify(dispatcher).publish(WebhookEvent.SUBSCRIPTION_ALLOTTED, allotted);
        verify(dispatcher).publish(WebhookEvent.TOKEN_LISTED, listed);
        verify(dispatcher).publish(WebhookEvent.TOKEN_SUSPENDED, suspended);
    }
}

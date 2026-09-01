package com.fracta.openapi.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.issuance.api.TokenListedEvent;
import com.fracta.subscription.api.SubscriptionAllottedEvent;
import com.fracta.trading.api.TradeEvents;

/** 개인 이벤트의 클라이언트 간 유출 방지. */
class WebhookOwnershipTest {

    private final WebhookEndpoint owner2 = new WebhookEndpoint(
            "cli_owner2", 2L, "https://example.com/hook", "whsec_test",
            Set.of(WebhookEvent.ORDER_FILLED, WebhookEvent.SUBSCRIPTION_ALLOTTED,
                    WebhookEvent.TOKEN_LISTED));

    @Test
    @DisplayName("주문·청약 이벤트는 같은 투자자 소유 클라이언트에만 전달")
    void privateEventsAreOwnerScoped() {
        var order = new TradeEvents.OrderFilled(1, "FR-T-001", 2, 3, 100);
        var otherOrder = new TradeEvents.OrderFilled(1, "FR-T-001", 99, 3, 100);
        var allotment = new SubscriptionAllottedEvent(1, 2, "FR-T-001", 2, 10);
        var otherAllotment = new SubscriptionAllottedEvent(1, 2, "FR-T-001", 99, 10);

        assertThat(WebhookDispatcher.belongsToOwner(owner2, order)).isTrue();
        assertThat(WebhookDispatcher.belongsToOwner(owner2, otherOrder)).isFalse();
        assertThat(WebhookDispatcher.belongsToOwner(owner2, allotment)).isTrue();
        assertThat(WebhookDispatcher.belongsToOwner(owner2, otherAllotment)).isFalse();
    }

    @Test
    @DisplayName("종목 상장·중단 같은 공개 이벤트는 모든 구독 클라이언트에 전달")
    void publicTokenEventsAreNotOwnerScoped() {
        assertThat(WebhookDispatcher.belongsToOwner(owner2,
                new TokenListedEvent(1, "FR-T-001", 100, 1_000))).isTrue();
    }
}

package com.fracta.openapi.webhook;

/** 웹훅 이벤트 종류 (FSD §7.4). */
public enum WebhookEvent {

    ORDER_FILLED("order.filled"),
    ORDER_PARTIALLY_FILLED("order.partially_filled"),
    ORDER_CANCELLED("order.cancelled"),
    SUBSCRIPTION_ALLOTTED("subscription.allotted"),
    TOKEN_LISTED("token.listed"),
    TOKEN_SUSPENDED("token.suspended");

    private final String value;

    WebhookEvent(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static WebhookEvent of(String value) {
        for (WebhookEvent event : values()) {
            if (event.value.equals(value)) {
                return event;
            }
        }
        throw new IllegalArgumentException("알 수 없는 웹훅 이벤트: " + value);
    }
}

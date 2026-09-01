package com.fracta.openapi.presentation;

/**
 * 오픈 API 응답의 식별자 표기 (FSD §7.3 예시: {@code "orderId": "ord_01HZ..."}).
 *
 * <p>내부 PK를 그대로 노출하지 않는다. 접두사가 붙어 있으면 외부 개발자가 어떤 자원의
 * 식별자인지 바로 알 수 있고, 다른 자원의 id를 잘못 넣는 실수도 걸러진다.
 */
public final class OpenApiIds {

    private static final String ORDER_PREFIX = "ord_";
    private static final String SUBSCRIPTION_PREFIX = "sub_";

    private OpenApiIds() {
    }

    public static String order(long id) {
        return ORDER_PREFIX + id;
    }

    public static long parseOrder(String value) {
        return parse(value, ORDER_PREFIX, "주문");
    }

    public static String subscription(long id) {
        return SUBSCRIPTION_PREFIX + id;
    }

    private static long parse(String value, String prefix, String label) {
        if (value == null || !value.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "%s 식별자 형식이 아니다 (%s로 시작해야 한다): %s".formatted(label, prefix, value));
        }
        try {
            return Long.parseLong(value.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("%s 식별자 형식이 아니다: %s".formatted(label, value));
        }
    }
}

package com.fracta.openapi.auth;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** 오픈 API 권한 범위 (FSD §7.2). */
public enum ApiScope {

    MARKET_READ("market:read"),
    ACCOUNT_READ("account:read"),
    ORDER_WRITE("order:write"),
    SUBSCRIPTION_WRITE("subscription:write");

    private final String value;

    ApiScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ApiScope of(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 scope: " + value));
    }

    /** 공백 구분 문자열 → scope 집합. */
    public static Set<ApiScope> parse(String spaceSeparated) {
        Set<ApiScope> scopes = new LinkedHashSet<>();
        if (spaceSeparated == null || spaceSeparated.isBlank()) {
            return scopes;
        }
        for (String token : spaceSeparated.trim().split("\\s+")) {
            scopes.add(of(token));
        }
        return scopes;
    }

    public static String join(Set<ApiScope> scopes) {
        return String.join(" ", scopes.stream().map(ApiScope::value).toList());
    }
}

package com.fracta.openapi.gateway;

import java.util.Set;

import com.fracta.openapi.ApiEnv;
import com.fracta.openapi.auth.ApiScope;

/** 인증된 오픈 API 요청의 주체. 필터가 채우고 컨트롤러가 읽는다. */
public record OpenApiContext(String clientId, long ownerInvestorId, Set<ApiScope> scopes, ApiEnv env) {

    private static final ThreadLocal<OpenApiContext> CURRENT = new ThreadLocal<>();

    public static OpenApiContext current() {
        OpenApiContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("오픈 API 컨텍스트가 없다 — 필터를 거치지 않은 호출이다");
        }
        return context;
    }

    public static void set(OpenApiContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public boolean hasScope(ApiScope scope) {
        return scopes.contains(scope);
    }
}

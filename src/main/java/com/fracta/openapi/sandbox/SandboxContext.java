package com.fracta.openapi.sandbox;

import com.fracta.openapi.ApiEnv;

/**
 * 현재 요청이 샌드박스인지 담는 스레드 컨텍스트.
 *
 * <p>도메인 코드에 {@code if (sandbox)} 분기를 흩뿌리지 않기 위해 존재한다
 * (phase-07 §흔한 실수 7). 데이터 격리는 이 값으로 커넥션의 스키마를 고르는 것으로만 이뤄진다.
 */
public final class SandboxContext {

    private static final ThreadLocal<ApiEnv> CURRENT = ThreadLocal.withInitial(() -> ApiEnv.LIVE);

    private SandboxContext() {
    }

    public static ApiEnv current() {
        return CURRENT.get();
    }

    public static boolean isSandbox() {
        return CURRENT.get() == ApiEnv.SANDBOX;
    }

    public static void set(ApiEnv env) {
        CURRENT.set(env);
    }

    public static void clear() {
        CURRENT.remove();
    }
}

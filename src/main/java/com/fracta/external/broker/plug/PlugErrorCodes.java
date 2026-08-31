package com.fracta.external.broker.plug;

import java.util.Set;

/**
 * PLUG 게이트웨이 오류코드(IGW*) 분류. 전체 표는 {@code docs/reference/plug-error-codes.md}.
 *
 * <p>업무 응답코드({@code rsp_cd})와는 별개 체계다. 재시도 여부가 코드마다 다르므로
 * 여기서 한 곳에 모아 판단한다 — 특히 <b>유량 초과에 토큰을 재발급하면 안 된다</b>.
 */
public final class PlugErrorCodes {

    /** 업무 응답 성공 코드 (공식 SDK 기준). */
    public static final Set<String> SUCCESS_RSP_CODES = Set.of("00000", "00166", "00221", "13578");

    /** 토큰 무효·만료 — 이 경우에만 재발급 후 1회 재시도한다. */
    private static final Set<String> TOKEN_INVALID = Set.of("IGW40043", "IGW40044");

    /** 유량 초과 — 재시도·재발급 모두 금지. 간격을 늘린다. */
    private static final Set<String> RATE_LIMITED = Set.of("IGW42901", "IGW42902", "IGW42903");

    /** 미지원 URI — 모의 도메인 미지원 API 판별 신호. Mock 폴백 대상. */
    private static final String UNSUPPORTED_URI = "IGW40401";

    /** 일시적 서버 오류 — 백오프 후 재시도 가능. */
    private static final Set<String> TRANSIENT = Set.of(
            "IGW50001", "IGW50002", "IGW50007", "IGW50011", "IGW50012", "IGW50020", "IGW50025");

    private PlugErrorCodes() {
    }

    public static boolean isSuccess(String rspCd) {
        return rspCd == null || SUCCESS_RSP_CODES.contains(rspCd);
    }

    public static boolean isTokenInvalid(String code) {
        return code != null && TOKEN_INVALID.contains(code);
    }

    public static boolean isRateLimited(String code) {
        return code != null && RATE_LIMITED.contains(code);
    }

    public static boolean isUnsupportedUri(String code) {
        return UNSUPPORTED_URI.equals(code);
    }

    public static boolean isTransient(String code) {
        return code != null && TRANSIENT.contains(code);
    }
}

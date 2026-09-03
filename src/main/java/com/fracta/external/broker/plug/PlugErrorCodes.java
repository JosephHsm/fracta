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

    /**
     * 모의 도메인이 제공하지 않는 API — Mock 폴백 대상.
     *
     * <p>두 코드가 같은 상황을 서로 다르게 말한다.
     * <ul>
     *   <li>{@code IGW40401} "제공하지 않는 API URI 입니다"</li>
     *   <li>{@code IGW40023} "모의투자에서는 제공하지 않는 API입니다. 실전투자 환경을 이용해주세요."</li>
     * </ul>
     *
     * <p>처음에는 40401만 넣었다. 2026-09-03에 모의 도메인이 시세 4종
     * (currentPrice·currentDaily·period·etfCurrent)을 전부 막으면서 <b>40023</b>으로
     * 응답하기 시작했고, 폴백이 발동하지 않아 조회가 예외로 떨어졌다.
     * 8월 31일 실측 때는 같은 엔드포인트가 정상 응답했다(scripts/plug/captured/ 참조).
     */
    private static final Set<String> UNSUPPORTED_ON_MOCK = Set.of("IGW40401", "IGW40023");

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
        return code != null && UNSUPPORTED_ON_MOCK.contains(code);
    }

    public static boolean isTransient(String code) {
        return code != null && TRANSIENT.contains(code);
    }
}

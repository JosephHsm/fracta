package com.fracta.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인 에러 코드. 접두사 체계(FSD §7.1): AUTH_ / VALID_ / STATE_ / FUND_ / RATE_ / IDEM_ / SUIT_
 * 각 Phase에서 필요한 코드를 이 enum에 추가한다.
 *
 * <p><b>문체 — 여기 문자열은 합니다체다.</b> 주석·설계 문서의 해라체를 쓰면 안 된다.
 * 이 값은 오픈 API 응답의 `error.message`로 외부 개발자에게 그대로 나가고,
 * 개발자 포털 샌드박스 콘솔에도 응답 원문이 표시된다 — 즉 화면 문구다.
 * (투자자 웹앱은 코드→문구 매핑표를 따로 두므로 이 문자열을 쓰지 않는다:
 * `packages/ui/src/lib/error-messages.ts`)
 * 회귀 테스트: {@code ErrorCodeStyleTest}
 */
public enum ErrorCode {

    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    VALID_INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 유효하지 않습니다."),
    VALID_AMOUNT_OVERFLOW(HttpStatus.BAD_REQUEST, "금액·수량 계산 범위를 초과했습니다."),
    VALID_UNITS_RANGE(HttpStatus.BAD_REQUEST, "수량이 허용 범위를 벗어났습니다."),
    VALID_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 등록된 이메일입니다."),
    STATE_INVALID_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 상태 전이입니다."),
    STATE_NOT_SUBSCRIBING(HttpStatus.CONFLICT, "청약 가능한 상태가 아닙니다."),
    STATE_NOT_TRADABLE(HttpStatus.CONFLICT, "거래 가능한 종목이 아닙니다."),
    STATE_NO_LIQUIDITY(HttpStatus.CONFLICT, "체결 가능한 반대편 호가가 없습니다."),
    STATE_LOCK_TIMEOUT(HttpStatus.CONFLICT, "처리 락 획득에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    IDEM_KEY_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "동일 멱등성 키에 다른 본문이 들어왔습니다."),
    IDEM_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
    IDEM_IN_PROGRESS(HttpStatus.CONFLICT, "같은 멱등성 키 요청이 처리 중입니다."),
    AUTH_INVALID_CLIENT(HttpStatus.UNAUTHORIZED, "client_id 또는 client_secret이 올바르지 않습니다."),
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    AUTH_SCOPE_DENIED(HttpStatus.FORBIDDEN, "필요한 scope가 없습니다."),
    AUTH_ENV_MISMATCH(HttpStatus.FORBIDDEN, "클라이언트 환경과 호출 경로가 맞지 않습니다."),
    FUND_INSUFFICIENT_UNITS(HttpStatus.CONFLICT, "보유 수량이 부족합니다."),
    FUND_INSUFFICIENT_CASH(HttpStatus.CONFLICT, "예치금이 부족합니다."),
    SUIT_PROFILE_MISMATCH(HttpStatus.FORBIDDEN, "투자자 성향등급보다 위험한 상품입니다."),
    SUIT_PROFILE_REQUIRED(HttpStatus.FORBIDDEN, "유효한 투자성향 진단이 필요합니다."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "호출 한도를 초과했습니다."),
    BROKER_AUTH_FAILED(HttpStatus.BAD_GATEWAY, "증권사 API 인증에 실패했습니다."),
    BROKER_CALL_FAILED(HttpStatus.BAD_GATEWAY, "증권사 API 호출에 실패했습니다."),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서비스를 사용할 수 없습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

package com.fracta.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인 에러 코드. 접두사 체계(FSD §7.1): AUTH_ / VALID_ / STATE_ / FUND_ / RATE_ / IDEM_ / SUIT_
 * 각 Phase에서 필요한 코드를 이 enum에 추가한다.
 */
public enum ErrorCode {

    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요하다"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않다"),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없다"),
    VALID_INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 유효하지 않다"),
    VALID_AMOUNT_OVERFLOW(HttpStatus.BAD_REQUEST, "금액·수량 계산 범위를 초과했다"),
    VALID_UNITS_RANGE(HttpStatus.BAD_REQUEST, "수량이 허용 범위를 벗어났다"),
    VALID_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 등록된 이메일이다"),
    STATE_INVALID_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 상태 전이다"),
    STATE_NOT_SUBSCRIBING(HttpStatus.CONFLICT, "청약 가능한 상태가 아니다"),
    STATE_NOT_TRADABLE(HttpStatus.CONFLICT, "거래 가능한 종목이 아니다"),
    STATE_NO_LIQUIDITY(HttpStatus.CONFLICT, "체결 가능한 반대편 호가가 없다"),
    STATE_LOCK_TIMEOUT(HttpStatus.CONFLICT, "처리 락 획득에 실패했다. 잠시 후 재시도하라"),
    IDEM_KEY_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "동일 멱등성 키에 다른 본문이 들어왔다"),
    IDEM_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요하다"),
    IDEM_IN_PROGRESS(HttpStatus.CONFLICT, "같은 멱등성 키 요청이 처리 중이다"),
    AUTH_INVALID_CLIENT(HttpStatus.UNAUTHORIZED, "client_id 또는 client_secret 이 올바르지 않다"),
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰이다"),
    AUTH_SCOPE_DENIED(HttpStatus.FORBIDDEN, "필요한 scope 가 없다"),
    AUTH_ENV_MISMATCH(HttpStatus.FORBIDDEN, "클라이언트 환경과 호출 경로가 맞지 않다"),
    FUND_INSUFFICIENT_UNITS(HttpStatus.CONFLICT, "보유 수량이 부족하다"),
    FUND_INSUFFICIENT_CASH(HttpStatus.CONFLICT, "예치금이 부족하다"),
    SUIT_PROFILE_MISMATCH(HttpStatus.FORBIDDEN, "투자자 성향등급보다 위험한 상품이다"),
    SUIT_PROFILE_REQUIRED(HttpStatus.FORBIDDEN, "유효한 투자성향 진단이 필요하다"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "호출 한도를 초과했다"),
    BROKER_AUTH_FAILED(HttpStatus.BAD_GATEWAY, "증권사 API 인증에 실패했다"),
    BROKER_CALL_FAILED(HttpStatus.BAD_GATEWAY, "증권사 API 호출에 실패했다"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서비스를 사용할 수 없다");

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

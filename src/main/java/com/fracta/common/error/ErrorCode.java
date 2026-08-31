package com.fracta.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인 에러 코드. 접두사 체계(FSD §7.1): AUTH_ / VALID_ / STATE_ / FUND_ / RATE_ / IDEM_ / SUIT_
 * 각 Phase에서 필요한 코드를 이 enum에 추가한다.
 */
public enum ErrorCode {

    VALID_INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 유효하지 않다"),
    VALID_AMOUNT_OVERFLOW(HttpStatus.BAD_REQUEST, "금액·수량 계산 범위를 초과했다"),
    FUND_INSUFFICIENT_UNITS(HttpStatus.CONFLICT, "보유 수량이 부족하다");

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

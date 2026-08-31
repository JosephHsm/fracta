package com.fracta.common.error;

import java.util.Map;

/** 모든 도메인 예외의 부모. 각 모듈은 이 클래스를 상속한 구체 예외를 던진다. */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    protected DomainException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), Map.of(), null);
    }

    protected DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    protected DomainException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    protected DomainException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}

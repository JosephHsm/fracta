package com.fracta.openapi.auth;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 오픈 API 도메인 예외 모음. */
public final class OpenApiExceptions {

    private OpenApiExceptions() {
    }

    public static class InvalidClientException extends DomainException {
        public InvalidClientException(String clientId) {
            super(ErrorCode.AUTH_INVALID_CLIENT,
                    "client_id 또는 client_secret 이 올바르지 않다",
                    Map.of("clientId", clientId == null ? "" : clientId));
        }
    }

    public static class ScopeDeniedException extends DomainException {
        public ScopeDeniedException(String required, String granted) {
            super(ErrorCode.AUTH_SCOPE_DENIED,
                    "이 엔드포인트에는 '%s' scope 가 필요하다".formatted(required),
                    Map.of("required", required, "granted", granted));
        }
    }

    public static class EnvMismatchException extends DomainException {
        public EnvMismatchException(String clientEnv, String pathEnv) {
            super(ErrorCode.AUTH_ENV_MISMATCH,
                    "%s 클라이언트는 %s 경로를 호출할 수 없다".formatted(clientEnv, pathEnv),
                    Map.of("clientEnv", clientEnv, "pathEnv", pathEnv));
        }
    }

    public static class IdempotencyKeyRequiredException extends DomainException {
        public IdempotencyKeyRequiredException() {
            super(ErrorCode.IDEM_KEY_REQUIRED, "Idempotency-Key 헤더가 필요하다", Map.of());
        }
    }

    public static class InvalidIdempotencyKeyException extends DomainException {
        public InvalidIdempotencyKeyException(int length) {
            super(ErrorCode.VALID_INVALID_INPUT,
                    "Idempotency-Key는 1~100자여야 한다", Map.of("length", length));
        }
    }

    public static class IdempotencyInProgressException extends DomainException {
        public IdempotencyInProgressException(String key) {
            super(ErrorCode.IDEM_IN_PROGRESS,
                    "같은 Idempotency-Key 요청이 처리 중이다", Map.of("idempotencyKey", key));
        }
    }

    public static class IdempotencyBodyMismatchException extends DomainException {
        public IdempotencyBodyMismatchException(String key) {
            super(ErrorCode.IDEM_KEY_CONFLICT,
                    "같은 Idempotency-Key 로 다른 본문이 들어왔다", Map.of("idempotencyKey", key));
        }
    }
}

package com.fracta.common.response;

import java.util.Map;

/** 실패 응답 공통 포맷(FSD §7.1): {@code { "error": { code, message, details }, "meta": ... }} */
public record ErrorResponse(ErrorBody error, ResponseMeta meta) {

    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(new ErrorBody(code, message, details), ResponseMeta.create());
    }
}

package com.fracta.common.response;

/** 성공 응답 공통 포맷(FSD §7.1): {@code { "data": ..., "meta": { requestId, timestamp } }} */
public record ApiResponse<T>(T data, ResponseMeta meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, ResponseMeta.create());
    }
}

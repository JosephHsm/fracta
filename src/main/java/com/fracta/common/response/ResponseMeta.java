package com.fracta.common.response;

import java.time.Instant;

import org.slf4j.MDC;

import com.fracta.common.logging.RequestIdFilter;

/** 모든 응답에 붙는 메타 — requestId는 MDC에서 읽는다. */
public record ResponseMeta(String requestId, Instant timestamp) {

    public static ResponseMeta create() {
        return new ResponseMeta(MDC.get(RequestIdFilter.REQUEST_ID_KEY), Instant.now());
    }
}

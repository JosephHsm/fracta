package com.fracta.common.logging;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 UUID requestId를 생성해 MDC에 넣고 응답 헤더 {@code X-Request-Id}로 반영한다.
 * 채널(WEB/API)도 경로로 판별해 MDC에 넣는다 — 감사 로그 AOP가 이 값을 읽는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String CHANNEL_KEY = "channel";
    public static final String HEADER_NAME = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID_KEY, requestId);
        MDC.put(CHANNEL_KEY, resolveChannel(request.getRequestURI()));
        response.setHeader(HEADER_NAME, requestId);
        try {
            chain.doFilter(request, response);
            log.info("http request completed: {} {} status={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus());
        } finally {
            MDC.remove(REQUEST_ID_KEY);
            MDC.remove(CHANNEL_KEY);
        }
    }

    private String resolveChannel(String uri) {
        if (uri.startsWith("/api/v1/admin/")) {
            return "ADMIN";
        }
        return uri.startsWith("/open") ? "API" : "WEB";
    }
}

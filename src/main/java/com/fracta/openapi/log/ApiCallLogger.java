package com.fracta.openapi.log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호출 로그 (OA-10) — <b>비동기</b> 기록. 동기 INSERT 하면 조회 API p95 목표를 넘긴다
 * (phase-07 §흔한 실수 8).
 *
 * <p>요청/응답 본문은 저장하지 않는다. 개인정보·용량 문제이며, 필요할 때 대조할 수 있도록
 * 해시만 남긴다.
 */
@Component
public class ApiCallLogger {

    private static final Logger log = LoggerFactory.getLogger(ApiCallLogger.class);

    private final ApiCallLogRepository repository;

    public ApiCallLogger(ApiCallLogRepository repository) {
        this.repository = repository;
    }

    @Async("apiCallLogExecutor")
    @Transactional
    public void record(String clientId, String endpoint, String method, int statusCode,
                       long latencyMs, String idempotencyKey, String rawBody) {
        if (clientId == null) {
            return;   // 인증 전에 실패한 요청은 클라이언트를 특정할 수 없다
        }
        try {
            repository.save(new ApiCallLog(clientId, endpoint, method, statusCode, latencyMs,
                    idempotencyKey, hashOrNull(rawBody)));
        } catch (Exception e) {
            // 로그 기록 실패가 API 응답에 영향을 주면 안 된다
            log.warn("API 호출 로그 기록 실패: client={} endpoint={}", clientId, endpoint, e);
        }
    }

    private String hashOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}

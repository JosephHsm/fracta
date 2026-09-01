package com.fracta.openapi.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.openapi.auth.OpenApiExceptions;

/**
 * 멱등성 처리 (OA-06, FSD §8.5).
 *
 * <pre>
 * SETNX idem:{clientId}:{key} = PROCESSING (TTL 24h)
 *   성공 → 실제 처리 후 응답 저장
 *   실패 → 저장된 값이 PROCESSING 이면 409, 응답이면 그대로 재생
 * 같은 키인데 본문이 다르면 → 422
 * </pre>
 *
 * <p><b>처리 중 예외가 나면 PROCESSING 키를 반드시 지운다.</b> 안 지우면 24시간 동안
 * 재시도가 막힌다 (phase-07 §흔한 실수 1 — 가장 흔한 버그).
 *
 * <p>본문 해시는 JSON을 정규화한 뒤 계산한다. 공백이나 키 순서 차이로 오탐이 나지 않게 한다.
 */
@Service
public class IdempotencyService {

    static final Duration TTL = Duration.ofHours(24);
    static final String PROCESSING = "PROCESSING";
    private static final String SEPARATOR = "\n";

    public record StoredResponse(int status, String body) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public IdempotencyService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 처리 권한을 잡는다.
     *
     * @return 비어 있으면 이번 요청이 처리 주체다. 값이 있으면 재생할 응답이다
     * @throws OpenApiExceptions.IdempotencyInProgressException 다른 요청이 처리 중
     * @throws OpenApiExceptions.IdempotencyBodyMismatchException 같은 키에 다른 본문
     */
    public Optional<StoredResponse> begin(String clientId, String key, String method,
                                          String path, String rawBody) {
        String redisKey = key(clientId, key);
        String requestHash = requestHash(method, path, rawBody);

        Boolean acquired = redis.opsForValue()
                .setIfAbsent(redisKey, PROCESSING + SEPARATOR + requestHash, TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }

        String existing = redis.opsForValue().get(redisKey);
        if (existing == null) {
            // SETNX 실패 직후 기존 키가 만료될 수 있다. 무조건 SET하면 다른 재시도 요청과
            // 동시에 처리 주체가 되므로 반드시 SETNX로 다시 경쟁한다.
            Boolean reacquired = redis.opsForValue()
                    .setIfAbsent(redisKey, PROCESSING + SEPARATOR + requestHash, TTL);
            if (Boolean.TRUE.equals(reacquired)) {
                return Optional.empty();
            }
            existing = redis.opsForValue().get(redisKey);
            if (existing == null) {
                throw new OpenApiExceptions.IdempotencyInProgressException(key);
            }
        }

        String[] parts = existing.split(SEPARATOR, 3);
        String storedHash = parts.length > 1 ? parts[1] : "";
        if (!storedHash.equals(requestHash)) {
            throw new OpenApiExceptions.IdempotencyBodyMismatchException(key);
        }
        if (PROCESSING.equals(parts[0])) {
            throw new OpenApiExceptions.IdempotencyInProgressException(key);
        }
        return Optional.of(new StoredResponse(Integer.parseInt(parts[0]),
                parts.length > 2 ? parts[2] : ""));
    }

    /** 처리 성공 — 응답을 저장해 이후 재요청이 같은 결과를 받게 한다. */
    public void complete(String clientId, String key, String method, String path,
                         String rawBody, int status, String responseBody) {
        redis.opsForValue().set(key(clientId, key),
                status + SEPARATOR + requestHash(method, path, rawBody) + SEPARATOR + responseBody, TTL);
    }

    /** 처리 실패 — PROCESSING 키를 지워 재시도를 열어준다. 이걸 빠뜨리면 24시간 잠긴다. */
    public void abort(String clientId, String key) {
        redis.delete(key(clientId, key));
    }

    private String key(String clientId, String idempotencyKey) {
        return "idem:" + clientId + ":" + idempotencyKey;
    }

    /** 같은 키를 다른 메서드·리소스에 재사용하는 것도 충돌로 판정한다. */
    private String requestHash(String method, String path, String rawBody) {
        return sha256(method + SEPARATOR + path + SEPARATOR + bodyHash(rawBody));
    }

    /** JSON을 정규화(키 정렬·공백 제거)한 뒤 해시한다. */
    String bodyHash(String rawBody) {
        String normalized = rawBody == null ? "" : rawBody;
        try {
            JsonNode node = objectMapper.readTree(normalized);
            normalized = objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(objectMapper.treeToValue(node, java.util.TreeMap.class));
        } catch (Exception e) {
            // JSON이 아니면 원문 그대로 해시한다
            normalized = normalized.trim();
        }
        return sha256(normalized);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", e);
        }
    }
}

package com.fracta.common.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 슬라이딩 윈도우 카운터 (Redis Sorted Set) — 증권사 쿼터(Phase 5)와 오픈 API 쿼터(Phase 7)가
 * <b>같은 구현을 공유한다</b>. 같은 알고리즘을 두 번 만들지 않는다.
 *
 * <p>고정 윈도우는 창 경계에서 버스트를 허용한다. 초당 N건 제한에서 0.9초에 N건, 1.1초에 N건을
 * 보내면 0.2초 사이에 2N건이 나간다. 슬라이딩은 "지금부터 과거 window"를 매번 다시 세므로
 * 그 구멍이 없다. (Phase 5에서 실측으로 확인 — `docs/benchmarks/broker-quota.md` §3)
 */
@Component
public class SlidingWindowCounter {

    /** 정리 → 카운트 → 조건부 추가를 한 번에 수행해 동시 요청의 초과 허용을 막는다. */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local used = redis.call('ZCARD', key)
            local allowed = 0
            if used < limit then
                redis.call('ZADD', key, now, member)
                used = used + 1
                allowed = 1
            end
            redis.call('PEXPIRE', key, window * 2)

            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local resetAt = now + window
            if oldest[2] ~= nil then
                resetAt = tonumber(oldest[2]) + window
            end
            return {allowed, used, resetAt}
            """, List.class);

    public record Decision(boolean allowed, long used, long limit, long resetEpochSeconds) {

        public long remaining() {
            return Math.max(0, limit - used);
        }
    }

    private final StringRedisTemplate redis;

    public SlidingWindowCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 한 건을 소비 시도한다. 거절되면 카운트를 늘리지 않는다. */
    public Decision tryAcquire(String key, long limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        if (limit <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("limit과 window는 양수여야 한다");
        }
        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) redis.execute(ACQUIRE_SCRIPT, List.of(key),
                String.valueOf(now), String.valueOf(windowMillis), String.valueOf(limit),
                now + ":" + UUID.randomUUID());
        if (result == null || result.size() != 3) {
            throw new IllegalStateException("Redis 슬라이딩 윈도우 결과가 올바르지 않다");
        }
        boolean allowed = result.get(0) == 1L;
        long used = result.get(1);
        long resetEpochSeconds = Math.floorDiv(result.get(2) + 999, 1_000);
        return new Decision(allowed, used, limit, resetEpochSeconds);
    }
}

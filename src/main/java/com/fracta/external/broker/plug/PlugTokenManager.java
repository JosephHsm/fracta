package com.fracta.external.broker.plug;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fracta.common.config.BrokerProperties;
import com.fracta.external.broker.api.BrokerTokenRefreshPort;

/**
 * PLUG 접근토큰 관리.
 *
 * <p>PLUG 고유 제약 두 가지를 코드로 못박는다.
 * <ul>
 *   <li><b>발급은 실전 도메인에서만</b> 가능하다. 발급받은 토큰은 모의·실전 호출 모두에 쓴다.</li>
 *   <li>토큰은 24시간({@code expires_in=86400}) 유효하다. 재기동마다 재발급하지 않도록
 *       Redis에 캐시한다 — 24시간 한도를 낭비하지 않기 위해서다.</li>
 * </ul>
 *
 * <p>동시 갱신 방지: 갱신은 한 스레드만 수행하고 나머지는 대기한 뒤 캐시된 값을 받는다
 * (발급 호출이 1회만 나가야 한다). 토큰 값은 어떤 경로로도 로그에 남기지 않는다.
 */
@Component
public class PlugTokenManager implements BrokerTokenRefreshPort {

    private static final Logger log = LoggerFactory.getLogger(PlugTokenManager.class);
    private static final String TOKEN_PATH = "/oauth2/token";

    private final BrokerProperties properties;
    private final StringRedisTemplate redis;
    private final RestClient restClient;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private final AtomicLong issueCount = new AtomicLong();

    /**
     * 캐시는 Redis 하나만 쓴다. 로컬 사본을 함께 두면 Redis 키가 사라졌을 때
     * (만료·flush·운영자 삭제) 로컬만 살아남아 서로 어긋난다.
     * 값에 만료 시각을 함께 담아 GET 한 번으로 판단한다.
     */
    private record CachedToken(String value, Instant expiresAt) {

        static final String SEPARATOR = "|";

        String serialize() {
            return expiresAt.getEpochSecond() + SEPARATOR + value;
        }

        static CachedToken parse(String raw) {
            int sep = raw.indexOf(SEPARATOR);
            if (sep <= 0) {
                return null;
            }
            try {
                Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(raw.substring(0, sep)));
                return new CachedToken(raw.substring(sep + 1), expiresAt);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public PlugTokenManager(BrokerProperties properties, StringRedisTemplate redis,
                            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.redis = redis;
        this.restClient = restClientBuilder.build();
    }

    /** 발급 호출 누적 횟수 — "동시 요청 N건 → 발급 1회" 검증용. */
    public long issueCount() {
        return issueCount.get();
    }

    public String accessToken() {
        CachedToken cached = readCache();
        if (cached != null && !needsRefresh(cached)) {
            return cached.value();
        }

        refreshLock.lock();
        try {
            // 대기하는 사이 다른 스레드가 갱신했을 수 있다
            CachedToken afterWait = readCache();
            if (afterWait != null && !needsRefresh(afterWait)) {
                return afterWait.value();
            }
            return issueAndCache().value();
        } finally {
            refreshLock.unlock();
        }
    }

    /** 만료 30분 전 선제 갱신 (설정값). 갱신 중 요청은 위 락에서 대기한다. */
    public void refreshIfDue() {
        try {
            refreshIfDueOrThrow();
        } catch (Exception e) {
            log.warn("토큰 선제 갱신 실패 — 다음 주기에 재시도한다: {}", e.getMessage());
        }
    }

    /** Phase 9 BrokerTokenRefreshJob 진입점. 실패를 삼키지 않아 Job 재시도가 작동한다. */
    @Override
    public void refreshIfDueOrThrow() {
        if (isBlank(properties.appKey())) {
            return;   // 자격증명 미설정 환경(로컬·CI)에서는 아무것도 하지 않는다
        }
        CachedToken cached = readCache();
        if (cached == null || needsRefresh(cached)) {
            accessToken();
        }
    }

    private boolean needsRefresh(CachedToken token) {
        Instant threshold = token.expiresAt().minusSeconds(properties.token().refreshMarginSeconds());
        return !Instant.now().isBefore(threshold);
    }

    private CachedToken readCache() {
        String raw = redis.opsForValue().get(cacheKey());
        return raw == null ? null : CachedToken.parse(raw);
    }

    private CachedToken issueAndCache() {
        if (isBlank(properties.appKey()) || isBlank(properties.appSecret())) {
            throw new IllegalStateException(
                    "BROKER_APP_KEY / BROKER_APP_SECRET 이 설정되지 않았다 (.env 확인)");
        }

        // 토큰 발급은 실전 도메인에서만 가능하다 — baseUrl(모의)이 아니라 authUrl을 쓴다.
        // URI 로 직접 넘긴다: 문자열 uri()는 템플릿으로 보고 다시 인코딩해서
        // '+' '=' 가 들어간 시크릿이 이중 인코딩으로 깨진다.
        java.net.URI uri = java.net.URI.create(properties.authUrl() + TOKEN_PATH
                + "?appkey=" + enc(properties.appKey())
                + "&appsecretkey=" + enc(properties.appSecret())
                + "&grant_type=client_credentials&scope=oob");

        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw BrokerApiException.transport(TOKEN_PATH, e);
        }

        if (body == null || body.get("access_token") == null) {
            throw BrokerApiException.business(TOKEN_PATH, "NO_TOKEN", "응답에 access_token 이 없다");
        }

        String token = String.valueOf(body.get("access_token"));
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 86_400L;

        CachedToken issued = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
        redis.opsForValue().set(cacheKey(), issued.serialize(), Duration.ofSeconds(expiresIn));
        issueCount.incrementAndGet();

        // 토큰 값은 절대 남기지 않는다 — 길이와 만료만 기록
        log.info("증권사 토큰 발급 완료 (길이 {}자, {}초 유효, 누적 발급 {}회)",
                token.length(), expiresIn, issueCount.get());
        return issued;
    }

    /** 401 수신 시 강제 재발급. 429에서는 절대 호출하지 않는다. */
    public String forceRefresh() {
        refreshLock.lock();
        try {
            redis.delete(cacheKey());
            return issueAndCache().value();
        } finally {
            refreshLock.unlock();
        }
    }

    private String cacheKey() {
        // 앱키가 바뀌면 캐시도 갈라져야 한다. 키 자체는 저장하지 않고 해시 일부만 쓴다
        String suffix = isBlank(properties.appKey()) ? "none"
                : Integer.toHexString(properties.appKey().hashCode());
        return properties.token().cachePrefix() + ":" + properties.env() + ":" + suffix;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

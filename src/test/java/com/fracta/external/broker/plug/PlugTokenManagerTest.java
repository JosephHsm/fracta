package com.fracta.external.broker.plug;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 토큰 수명 관리. 발급 호출 횟수를 WireMock 이 세므로 "1회만" 을 실제로 검증할 수 있다.
 * refresh-margin 을 크게 두어 24시간을 기다리지 않고 선제 갱신 경로를 태운다 (압축 검증).
 */
@TestPropertySource(properties = {
        "broker.token.refresh-margin-seconds=1800",
        "broker.token.cache-prefix=fracta:test:token"
})
class PlugTokenManagerTest extends PlugIntegrationTestBase {

    @Autowired
    PlugTokenManager tokenManager;

    @Autowired
    StringRedisTemplate redis;

    private void stubToken(String body) {
        authServer.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void clearCache() {
        redis.keys("fracta:test:token*").forEach(redis::delete);
    }

    @Test
    @DisplayName("토큰 갱신 중 동시 요청 10건 → 발급 호출은 1회만 발생한다")
    void concurrentRequestsIssueTokenOnce() throws Exception {
        clearCache();
        // 발급이 느린 상황을 만들어 동시 진입을 유도한다
        authServer.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(300)
                        .withBody(readStub("token-200.json"))));

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        assertThat(tokenManager.accessToken()).isNotBlank();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isZero();
        authServer.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));
    }

    @Test
    @DisplayName("유효한 토큰은 캐시에서 재사용한다 — 재기동·반복 호출로 발급을 낭비하지 않는다")
    void cachedTokenIsReused() {
        clearCache();
        stubToken(readStub("token-200.json"));

        String first = tokenManager.accessToken();
        for (int i = 0; i < 5; i++) {
            assertThat(tokenManager.accessToken()).isEqualTo(first);
        }
        authServer.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));
    }

    @Test
    @DisplayName("만료가 갱신 여유(30분) 안으로 들어오면 선제 갱신한다")
    void preemptiveRefreshWhenNearExpiry() {
        clearCache();
        // expires_in 을 갱신 여유(1800초)보다 짧게 주면 즉시 갱신 대상이 된다
        stubToken("""
                {"access_token":"SHORT_LIVED","scope":"oob","token_type":"Bearer","expires_in":600}
                """);

        tokenManager.accessToken();
        tokenManager.accessToken();   // 캐시가 있어도 갱신 여유 안이므로 다시 발급한다

        assertThat(authServer.findAll(postRequestedFor(urlPathEqualTo("/oauth2/token"))).size())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("토큰 값은 Redis 캐시에만 있고 로그·감사에는 남지 않는다")
    void tokenNotLeaked() {
        clearCache();
        stubToken(readStub("token-200.json"));
        String token = tokenManager.accessToken();

        // 캐시에는 있다
        assertThat(redis.keys("fracta:test:token*")).isNotEmpty();
        // 토큰 문자열이 감사 로그에 들어가지 않는다
        Long leaked = jdbcTokenLeakCount(token);
        assertThat(leaked).isZero();
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long jdbcTokenLeakCount(String token) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE before_state::text LIKE ? OR after_state::text LIKE ?",
                Long.class, "%" + token + "%", "%" + token + "%");
    }
}

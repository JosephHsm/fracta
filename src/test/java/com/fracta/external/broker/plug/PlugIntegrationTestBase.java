package com.fracta.external.broker.plug;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fracta.support.IntegrationTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * WireMock을 증권사로 세워 두고 도는 통합 테스트 기반.
 *
 * <p>{@code plug} 프로파일을 켜서 실제 어댑터를 활성화한다. 스텁은 실제 캡처 응답이다
 * ({@code src/test/resources/wiremock/plug/README.md}).
 *
 * <p>{@code BrokerSafetyValidator}는 켜진 채로 돈다 — 루프백 주소만 예외로 허용되고
 * 실전 도메인은 여전히 부팅을 막는다 ({@code BrokerSafetyValidatorTest} 가 검증).
 */
@ActiveProfiles({"test", "plug"})
public abstract class PlugIntegrationTestBase extends IntegrationTestBase {

    protected static WireMockServer authServer;    // 실전 도메인 역할 (토큰 발급 전용)
    protected static WireMockServer dataServer;    // 모의 도메인 역할 (시세 조회)

    @BeforeAll
    static void startWireMock() {
        authServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        dataServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        authServer.start();
        dataServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (authServer != null) {
            authServer.stop();
        }
        if (dataServer != null) {
            dataServer.stop();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    protected org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetStubsAndTokenCache() {
        authServer.resetAll();
        dataServer.resetAll();
        // 토큰 캐시가 테스트 사이에 남으면 "발급 호출 N회" 검증이 앞 테스트에 오염된다
        for (String pattern : new String[]{"fracta:broker:token*", "fracta:test:token*"}) {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("broker.auth-url", () -> "http://localhost:" + authServer.port());
        registry.add("broker.base-url", () -> "http://localhost:" + dataServer.port());
        registry.add("broker.app-key", () -> "TEST-APP-KEY");
        registry.add("broker.app-secret", () -> "TEST-APP-SECRET");
        registry.add("broker.env", () -> "mock");
        registry.add("broker.account-product-code", () -> "03");
        registry.add("broker.allow-live", () -> "false");
        // 안전장치는 그대로 켠 채로 돈다 — 루프백(WireMock)만 예외로 허용되고
        // 실전 도메인은 여전히 부팅을 실패시킨다 (BrokerSafetyValidatorTest 가 검증).
        registry.add("broker.rate-limit.per-sec", () -> "4");
        registry.add("broker.rate-limit.max-wait-millis", () -> "2000");
    }

    protected static String readStub(String name) {
        try (var in = PlugIntegrationTestBase.class.getResourceAsStream("/wiremock/plug/" + name)) {
            if (in == null) {
                throw new IllegalStateException("스텁 파일이 없다: " + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("스텁 읽기 실패: " + name, e);
        }
    }
}

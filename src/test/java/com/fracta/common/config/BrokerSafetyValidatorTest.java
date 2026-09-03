package com.fracta.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 실주문 차단 보증.
 *
 * <p><b>2026-09-03 규칙 변경</b> — 모의 도메인이 시세를 전면 차단해(IGW40023) 시세 조회를
 * 실전 도메인으로 옮겼다. 그래서 "실전 도메인 거부"는 더 이상 보증이 아니다.
 * 대신 <b>경로</b>가 보증한다 — 조회 경로만 허용한다. 모의 도메인에 주문을 보내도 주문은
 * 나가므로, 경로가 도메인보다 정확한 기준이다.
 */
class BrokerSafetyValidatorTest {

    @Configuration
    @EnableConfigurationProperties(BrokerProperties.class)
    static class SafetyConfig {
        @Bean
        BrokerSafetyValidator brokerSafetyValidator(BrokerProperties properties) {
            return new BrokerSafetyValidator(properties);
        }
    }

    private ApplicationContextRunner runner(String... overrides) {
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withUserConfiguration(SafetyConfig.class)
                .withPropertyValues(
                        "broker.env=mock",
                        "broker.base-url=https://api.nhplug.com:8443",
                        "broker.endpoints.currentPrice=/krstock/quote/v1/currentPrice",
                        "broker.auth-url=https://api.nhplug.com:8443",
                        "broker.account-product-code=03",
                        "broker.allow-live=false");
        return base.withPropertyValues(overrides);
    }

    @Test
    @DisplayName("조회 전용 설정이면 실전 도메인이어도 정상 기동한다 — 나가는 건 시세뿐이다")
    void quoteOnlyConfigBootsSuccessfully() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BrokerSafetyValidator.class);
        });
    }

    @Test
    @DisplayName("계좌구분 01(실전)이면 컨텍스트 로딩이 실패한다")
    void liveAccountProductCodeFailsBoot() {
        runner("broker.account-product-code=01")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("계좌구분 02(실전)도 거부한다")
    void liveAccountProductCode02FailsBoot() {
        runner("broker.account-product-code=02")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("모의 도메인도 여전히 허용한다 — 차단이 풀리면 되돌릴 수 있어야 한다")
    void mockDomainStillAllowed() {
        runner("broker.base-url=https://moapi.nhplug.com:8443")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("★ 주문 경로가 설정에 있으면 부팅을 막는다 — 이게 실주문 차단의 핵심이다")
    void orderEndpointFailsBoot() {
        runner("broker.endpoints.cashBuy=/krstock/order/v1/cashBuy")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("조회라도 화이트리스트에 없으면 거부한다 — 잔고 조회도 막는다")
    void nonWhitelistedEndpointFailsBoot() {
        runner("broker.endpoints.balance=/krstock/inquiry/v1/balance")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("증권사 도메인이 아니면 거부한다")
    void foreignDomainFailsBoot() {
        runner("broker.base-url=https://evil.example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("broker.env가 mock이 아니면 거부한다")
    void nonMockEnvFailsBoot() {
        runner("broker.env=live")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("allow-live=true도 거부한다 — 이 빌드에 실전 경로는 없다")
    void allowLiveFlagFailsBoot() {
        runner("broker.allow-live=true")
                .run(context -> assertThat(context).hasFailed());
    }
}

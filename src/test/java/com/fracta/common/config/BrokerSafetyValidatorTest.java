package com.fracta.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                        "broker.base-url=https://moapi.nhplug.com:8443",
                        "broker.auth-url=https://api.nhplug.com:8443",
                        "broker.account-product-code=03",
                        "broker.allow-live=false");
        return base.withPropertyValues(overrides);
    }

    @Test
    @DisplayName("모의 설정(03, moapi.*)이면 컨텍스트가 정상 기동한다")
    void mockConfigBootsSuccessfully() {
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
    @DisplayName("실전 도메인(api.nhplug.com)이면 거부한다")
    void liveDomainFailsBoot() {
        runner("broker.base-url=https://api.nhplug.com:8443")
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

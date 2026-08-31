package com.fracta.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 증권사(namuh PLUG) 연동 설정. 부팅 시 {@link BrokerSafetyValidator}가 검증한다. */
@ConfigurationProperties(prefix = "broker")
public record BrokerProperties(
        String env,
        String baseUrl,
        String authUrl,
        String accountProductCode,
        boolean allowLive
) {
}

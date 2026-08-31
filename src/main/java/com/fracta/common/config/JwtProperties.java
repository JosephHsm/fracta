package com.fracta.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT 설정 — secret은 HS256 기준 32바이트 이상. */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(String secret, long ttlSeconds) {
}

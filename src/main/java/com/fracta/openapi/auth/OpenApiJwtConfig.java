package com.fracta.openapi.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * 오픈 API 전용 JWT 발급자.
 *
 * <p><b>웹앱 JWT와 시크릿·발급자를 완전히 분리한다</b> (phase-07 §흔한 실수 6).
 * 같이 쓰면 웹앱 토큰으로 오픈 API를 부르거나 그 반대가 가능해져 권한 경계가 무너진다.
 * 발급자(iss) 값도 다르게 두어 토큰을 서로 받아주지 않는다.
 */
@Configuration
public class OpenApiJwtConfig {

    /**
     * 발급자 식별자. Spring 의 {@code JwtIssuerValidator}는 {@code iss} 를 URL 로 파싱하므로
     * URI 형태여야 한다 — 평범한 문자열을 쓰면 검증 단계에서 토큰이 통째로 거부된다.
     */
    public static final String ISSUER = "https://fracta.local/open-api";

    @Bean("openApiJwtEncoder")
    JwtEncoder openApiJwtEncoder(@Value("${openapi.jwt.secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }

    @Bean("openApiJwtDecoder")
    JwtDecoder openApiJwtDecoder(@Value("${openapi.jwt.secret}") String secret) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(secret))
                .macAlgorithm(MacAlgorithm.HS256).build();
        // 웹앱 토큰이 흘러들어오면 발급자 검증에서 걸린다
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                .createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}

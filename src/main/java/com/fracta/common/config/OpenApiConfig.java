package com.fracta.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI 문서 설정.
 *
 * <p><b>왜 필요한가.</b> 스펙에 보안 스킴이 없으면 openapi-generator가 만든 클라이언트가
 * {@code Authorization} 헤더를 붙이는 코드를 아예 만들지 않는다. 서버는 정상인데
 * 프론트의 모든 인증 요청이 401로 떨어지고, 화면에는 "데이터 없음"으로만 보인다.
 * 실제로 그 사고를 냈다 — 스킴 선언은 문서 장식이 아니라 클라이언트 생성의 입력이다.
 *
 * <p>웹앱 JWT와 오픈 API 토큰 모두 {@code Authorization: Bearer ...} 형식이라 스킴 하나로
 * 덮는다. 발급 주체와 검증 경로가 다른 것은 {@code SecurityConfig}의 필터 체인이 가른다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI fractaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FRACTA API")
                        .version("v1")
                        .description("""
                                토큰증권 기반 조각투자 발행·유통 플랫폼 API.

                                - `/api/v1/**` : 투자자 웹앱용. 로그인으로 받은 JWT를 사용한다.
                                - `/open/**`   : 파트너 오픈 API. client_credentials로 받은 토큰을 사용한다.
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Authorization: Bearer {token}")))
                // 전역 요구사항으로 둔다. 로그인·가입은 토큰이 없어도 동작하지만,
                // 클라이언트가 헤더를 붙여 보내도 서버가 무시하므로 문제되지 않는다.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}

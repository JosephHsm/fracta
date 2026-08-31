package com.fracta.common.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 증권사(namuh PLUG) 연동 설정. 부팅 시 {@link BrokerSafetyValidator}가 검증한다.
 *
 * <p>앱키·시크릿은 저장소에 두지 않는다 — `.env`(gitignore)에서 환경변수로 주입된다.
 * 엔드포인트 경로는 하드코딩하지 않고 {@code endpoints} 맵으로 분리한다 (phase-05 §6-3).
 */
@ConfigurationProperties(prefix = "broker")
public record BrokerProperties(
        String env,
        String baseUrl,
        String authUrl,
        String accountProductCode,
        boolean allowLive,
        String appKey,
        String appSecret,
        Token token,
        RateLimit rateLimit,
        /** 논리 이름 → 경로. 예: {@code currentPrice: /krstock/quote/v1/currentPrice} */
        Map<String, String> endpoints,
        /** 모의 도메인에서 미지원인 논리 엔드포인트 이름 — Mock 폴백 대상 */
        java.util.Set<String> unsupportedOnMock
) {

    /** 토큰 수명 관리. PLUG 토큰은 24시간(expires_in=86400)이다. */
    public record Token(
            /** 만료 이 시간 전부터 선제 갱신한다 (기본 30분). 압축 검증 시 이 값을 키운다. */
            long refreshMarginSeconds,
            /** Redis 캐시 키 접두사 */
            String cachePrefix
    ) {
    }

    public record RateLimit(
            /** sliding | bucket */
            String strategy,
            /** 초당 허용 호출 수. 공식 문서 기준 "초당 5회 수준", SDK 기본 스로틀 4 */
            int perSec,
            /** 허용될 때까지 대기하는 최대 시간(ms). 초과 시 거절 */
            long maxWaitMillis
    ) {
    }

    public String endpoint(String logicalName) {
        String path = endpoints == null ? null : endpoints.get(logicalName);
        if (path == null) {
            throw new IllegalStateException(
                    "broker.endpoints 에 '%s' 경로가 없다. 설정으로 분리해야 한다".formatted(logicalName));
        }
        return path;
    }

    public boolean supportedOnCurrentEnv(String logicalName) {
        return unsupportedOnMock == null || !unsupportedOnMock.contains(logicalName);
    }
}

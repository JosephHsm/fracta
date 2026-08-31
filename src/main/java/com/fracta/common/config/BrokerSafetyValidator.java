package com.fracta.common.config;

import java.net.URI;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 실전 계좌 차단 안전장치. 모의 환경(계좌구분 03, moapi.* 도메인)이 아니면 부팅을 실패시킨다.
 * 이 검증을 우회·비활성화하는 코드를 작성하지 않는다 (CLAUDE.md).
 */
@Component
public class BrokerSafetyValidator {

    private final BrokerProperties properties;

    public BrokerSafetyValidator(BrokerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        // 실전 연동 코드는 이 코드베이스에 존재하지 않는다. 플래그가 켜져 있어도 부팅을 막는다.
        if (properties.allowLive()) {
            throw new IllegalStateException(
                    "broker.allow-live=true 는 지원하지 않는다. 이 빌드에는 실전 연동 경로가 없다.");
        }
        if (!"mock".equals(properties.env())) {
            throw new IllegalStateException(
                    "broker.env 는 'mock' 이어야 한다. 현재 값: " + properties.env());
        }
        if (!"03".equals(properties.accountProductCode())) {
            throw new IllegalStateException(
                    "broker.account-product-code 는 '03'(모의)이어야 한다. 현재 값: "
                            + properties.accountProductCode());
        }
        String host = properties.baseUrl() == null ? null : URI.create(properties.baseUrl()).getHost();
        if (host == null || !host.startsWith("moapi.")) {
            throw new IllegalStateException(
                    "broker.base-url 은 moapi.* (모의) 도메인이어야 한다. 현재 값: " + properties.baseUrl());
        }
    }
}

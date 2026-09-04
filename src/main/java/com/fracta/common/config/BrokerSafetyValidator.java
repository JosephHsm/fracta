package com.fracta.common.config;

import java.net.URI;

import org.springframework.stereotype.Component;

import com.fracta.external.broker.plug.PlugPathPolicy;

import jakarta.annotation.PostConstruct;

/**
 * 실주문 차단 안전장치. 조건을 어기면 부팅을 실패시킨다.
 * 이 검증을 우회·비활성화하는 코드를 작성하지 않는다 (CLAUDE.md).
 *
 * <p><b>2026-09-03 규칙 변경</b> — 모의 도메인이 시세를 전면 차단해(IGW40023) 시세 조회를
 * 실전 도메인으로 옮겼다. 그러면서 안전 근거를 <b>도메인에서 경로로</b> 옮겼다.
 * 위험을 결정하는 건 어느 서버냐가 아니라 무엇을 부르냐다 — 모의 도메인에 주문을 보내도
 * 주문은 나간다. 지금 규칙이 더 정확하다.
 *
 * <p>남아 있는 보증: 계좌구분 03 · allow-live 금지 · <b>조회 전용 경로만 허용</b>.
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
        if (host == null || !(host.endsWith(".nhplug.com") || isLoopback(host))) {
            throw new IllegalStateException(
                    "broker.base-url 은 nhplug.com 도메인이어야 한다. 현재 값: " + properties.baseUrl());
        }

        // 안전의 근거는 도메인이 아니라 경로다. 설정된 엔드포인트가 전부 조회인지 확인한다.
        // 모의 도메인이라도 주문 경로를 부르면 모의 주문이 나가고,
        // 실전 도메인이라도 시세 경로만 부르면 아무것도 체결되지 않는다.
        if (properties.endpoints() != null) {
            properties.endpoints().forEach((name, path) -> {
                if (!PlugPathPolicy.isAllowed(path)) {
                    throw new IllegalStateException(
                            "broker.endpoints.%s 가 조회 전용 경로가 아니다: %s".formatted(name, path));
                }
            });
        }
    }

    /**
     * 로컬 스텁(WireMock) 주소만 예외로 허용한다 — 루프백은 증권사가 아니다.
     *
     * <p>실제 증권사 도메인은 {@code .nhplug.com} 만 통과한다. (KIS 시절 주석이
     * {@code moapi.*} 로 남아 있었는데, v1.1에서 NH로 바꾸면서 코드만 고쳐졌다.)
     * 도메인 검사는 이제 보조 장치이고, 실주문 차단의 근거는 {@link PlugPathPolicy} 의
     * 조회 전용 경로 화이트리스트다.
     */
    private boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }
}

package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 오류코드 분류 — 잘못 분류하면 유량 초과에 토큰을 재발급하는 사고가 난다. */
class PlugErrorCodesTest {

    @Test
    @DisplayName("업무 성공 코드")
    void successCodes() {
        assertThat(PlugErrorCodes.isSuccess("00000")).isTrue();
        assertThat(PlugErrorCodes.isSuccess("00166")).isTrue();
        assertThat(PlugErrorCodes.isSuccess("00221")).isTrue();
        assertThat(PlugErrorCodes.isSuccess("13578")).isTrue();
        assertThat(PlugErrorCodes.isSuccess(null)).isTrue();       // 코드 없는 응답은 성공 취급
        assertThat(PlugErrorCodes.isSuccess("IGW40031")).isFalse();
    }

    @Test
    @DisplayName("토큰 무효·만료만 재발급 대상")
    void tokenInvalid() {
        assertThat(PlugErrorCodes.isTokenInvalid("IGW40043")).isTrue();
        assertThat(PlugErrorCodes.isTokenInvalid("IGW40044")).isTrue();
        // 유량 초과를 토큰 문제로 오인하면 안 된다
        assertThat(PlugErrorCodes.isTokenInvalid("IGW42901")).isFalse();
        assertThat(PlugErrorCodes.isTokenInvalid("IGW40031")).isFalse();
    }

    @Test
    @DisplayName("유량 초과 3종")
    void rateLimited() {
        assertThat(PlugErrorCodes.isRateLimited("IGW42901")).isTrue();
        assertThat(PlugErrorCodes.isRateLimited("IGW42902")).isTrue();
        assertThat(PlugErrorCodes.isRateLimited("IGW42903")).isTrue();
        assertThat(PlugErrorCodes.isRateLimited("IGW40043")).isFalse();
    }

    @Test
    @DisplayName("모의 도메인 미지원은 두 코드 모두 Mock 폴백 신호 — IGW40401과 IGW40023")
    void unsupportedUri() {
        assertThat(PlugErrorCodes.isUnsupportedUri("IGW40401")).isTrue();
        // 2026-09-03 모의 도메인이 시세 4종을 막으면서 실제로 온 코드.
        // 40401만 보고 있었기 때문에 폴백이 발동하지 않고 예외로 떨어졌다.
        assertThat(PlugErrorCodes.isUnsupportedUri("IGW40023")).isTrue();
        assertThat(PlugErrorCodes.isUnsupportedUri("IGW40301")).isFalse();
        assertThat(PlugErrorCodes.isUnsupportedUri(null)).isFalse();
    }

    @Test
    @DisplayName("일시적 서버 오류는 재시도 가능")
    void transientErrors() {
        assertThat(PlugErrorCodes.isTransient("IGW50025")).isTrue();
        assertThat(PlugErrorCodes.isTransient("IGW50011")).isTrue();
        assertThat(PlugErrorCodes.isTransient("IGW50012")).isTrue();
        assertThat(PlugErrorCodes.isTransient("IGW40031")).isFalse();   // 자격증명 오류는 재시도 무의미
    }
}

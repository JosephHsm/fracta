package com.fracta.support;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.fracta.account.application.AuthService;

/** 통합 테스트용 인증 헬퍼. 테스트 클래스패스에만 존재한다. */
@Component
public class AuthTestSupport {

    public record TestUser(long id, String email, String token) {
    }

    private final AuthService authService;

    public AuthTestSupport(AuthService authService) {
        this.authService = authService;
    }

    public TestUser signupAndLogin(String name) {
        String email = name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.io";
        long id = authService.signup(name, email, "password-123!").value();
        String token = authService.login(email, "password-123!").accessToken();
        return new TestUser(id, email, token);
    }

    /** ADMIN 토큰 — admin 계정 행 없이 토큰만 발급한다 (관리 API는 투자자 행이 필요 없다). */
    public String adminToken() {
        return authService.issueToken(999_999L, "test-admin", "ADMIN").accessToken();
    }

    public HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}

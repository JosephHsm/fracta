package com.fracta.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;

class AccountFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTestSupport auth;

    @Test
    @DisplayName("가입 → 로그인 → 3초 내 KYC VERIFIED → 성향 진단 제출")
    void signupLoginKycAndRiskProfile() throws Exception {
        String email = "flow-" + UUID.randomUUID().toString().substring(0, 8) + "@test.io";

        // 가입 (201)
        ResponseEntity<String> signup = rest.postForEntity("/api/v1/auth/signup",
                Map.of("name", "홍길동", "email", email, "password", "password-123!"), String.class);
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 중복 이메일 → 409
        ResponseEntity<String> dup = rest.postForEntity("/api/v1/auth/signup",
                Map.of("name", "홍길동", "email", email, "password", "password-123!"), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 로그인 → 토큰
        ResponseEntity<String> login = rest.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", "password-123!"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = objectMapper.readTree(login.getBody()).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();

        // 토큰 없이 보호 API → 401 (공통 실패 포맷)
        ResponseEntity<String> unauthorized = rest.getForEntity("/api/v1/investors/me", String.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(objectMapper.readTree(unauthorized.getBody()).path("error").path("code").asText())
                .isEqualTo("AUTH_UNAUTHORIZED");

        // KYC: 등록 3초 후 VERIFIED (여유 8초까지 폴링)
        String kycStatus = "";
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode me = objectMapper.readTree(get("/api/v1/investors/me", token).getBody());
            kycStatus = me.path("data").path("kycStatus").asText();
            if ("VERIFIED".equals(kycStatus)) {
                break;
            }
            Thread.sleep(300);
        }
        assertThat(kycStatus).isEqualTo("VERIFIED");

        // 성향 진단: 합 24 → 3등급 (위험중립형)
        ResponseEntity<String> profile = rest.exchange("/api/v1/investors/me/risk-profile", HttpMethod.POST,
                new HttpEntity<>(Map.of("answers", java.util.List.of(3, 3, 3, 3, 3, 3, 3, 3)),
                        auth.bearer(token)),
                String.class);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(profile.getBody()).path("data");
        assertThat(data.path("score").asInt()).isEqualTo(24);
        assertThat(data.path("grade").asInt()).isEqualTo(3);
    }

    private ResponseEntity<String> get(String url, String token) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth.bearer(token)), String.class);
    }
}

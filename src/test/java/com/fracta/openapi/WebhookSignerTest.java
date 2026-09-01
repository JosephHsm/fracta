package com.fracta.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.openapi.webhook.WebhookSigner;

/** 웹훅 HMAC 서명 (FSD §7.4, phase-07 §완료조건). */
class WebhookSignerTest {

    private static final String SECRET = "whsec_test_1234567890";
    private static final String BODY = "{\"orderId\":42,\"status\":\"FILLED\"}";
    private static final Instant T = Instant.ofEpochSecond(1_755_676_800L);

    @Test
    @DisplayName("서명 형식은 t={epoch},v1={hex64}")
    void signatureFormat() {
        String signature = WebhookSigner.sign(SECRET, BODY, T);
        assertThat(signature).matches("t=1755676800,v1=[0-9a-f]{64}");
    }

    @Test
    @DisplayName("생성한 서명은 같은 비밀·본문으로 검증된다")
    void signAndVerify() {
        String signature = WebhookSigner.sign(SECRET, BODY, T);
        assertThat(WebhookSigner.verify(SECRET, BODY, signature, T)).isTrue();
    }

    @Test
    @DisplayName("본문이 한 글자만 달라도 검증 실패")
    void tamperedBodyFails() {
        String signature = WebhookSigner.sign(SECRET, BODY, T);
        assertThat(WebhookSigner.verify(SECRET, BODY.replace("42", "43"), signature, T)).isFalse();
    }

    @Test
    @DisplayName("다른 비밀로는 검증 실패")
    void wrongSecretFails() {
        String signature = WebhookSigner.sign(SECRET, BODY, T);
        assertThat(WebhookSigner.verify("whsec_other", BODY, signature, T)).isFalse();
    }

    @Test
    @DisplayName("타임스탬프 ±5분 초과면 리플레이로 보고 거부한다")
    void replayWindow() {
        String signature = WebhookSigner.sign(SECRET, BODY, T);

        assertThat(WebhookSigner.verify(SECRET, BODY, signature, T.plus(Duration.ofMinutes(4))))
                .as("4분 뒤는 허용").isTrue();
        assertThat(WebhookSigner.verify(SECRET, BODY, signature, T.minus(Duration.ofMinutes(4))))
                .as("4분 전도 허용").isTrue();
        assertThat(WebhookSigner.verify(SECRET, BODY, signature, T.plus(Duration.ofMinutes(6))))
                .as("6분 뒤는 거부").isFalse();
        assertThat(WebhookSigner.verify(SECRET, BODY, signature, T.minus(Duration.ofMinutes(6))))
                .as("6분 전도 거부").isFalse();
    }

    @Test
    @DisplayName("형식이 깨진 헤더는 조용히 통과하지 않는다")
    void malformedHeader() {
        assertThat(WebhookSigner.verify(SECRET, BODY, null, T)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, BODY, "", T)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, BODY, "v1=abc", T)).isFalse();       // t 없음
        assertThat(WebhookSigner.verify(SECRET, BODY, "t=1755676800", T)).isFalse(); // v1 없음
        assertThat(WebhookSigner.verify(SECRET, BODY, "t=notanumber,v1=abc", T)).isFalse();
    }

    @Test
    @DisplayName("재직렬화된 본문으로는 검증이 깨진다 — raw body 그대로 서명해야 하는 이유")
    void reserializedBodyBreaksVerification() {
        String signature = WebhookSigner.sign(SECRET, BODY, T);
        String reserialized = "{ \"orderId\" : 42, \"status\" : \"FILLED\" }";   // 같은 뜻, 다른 문자열
        assertThat(WebhookSigner.verify(SECRET, reserialized, signature, T)).isFalse();
    }
}

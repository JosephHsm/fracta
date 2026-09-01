package com.fracta.openapi.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 웹훅 HMAC 서명 (FSD §7.4) — 순수 함수.
 *
 * <pre>
 * X-Fracta-Signature: t={epochSeconds},v1={hex}
 * v1 = HMAC_SHA256(secret, "{t}.{raw_body}")
 * </pre>
 *
 * <p><b>raw_body 를 직렬화된 그대로</b> 서명한다. 재직렬화하면 공백·키 순서가 달라져
 * 수신 측 검증이 깨진다 (phase-07 §흔한 실수 2).
 *
 * <p>비교는 {@link MessageDigest#isEqual}로 한다. {@code String.equals}는 앞에서부터 비교하다
 * 다르면 즉시 끝나 타이밍 공격에 노출된다 (§흔한 실수 3).
 */
public final class WebhookSigner {

    public static final String HEADER = "X-Fracta-Signature";
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    public static String sign(String secret, String rawBody, Instant timestamp) {
        long t = timestamp.getEpochSecond();
        return "t=" + t + ",v1=" + hmacHex(secret, t + "." + rawBody);
    }

    /** 타임스탬프가 ±5분 밖이면 리플레이로 보고 거부한다. */
    public static boolean verify(String secret, String rawBody, String header, Instant now) {
        if (header == null) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0].trim())) {
                try {
                    timestamp = Long.parseLong(kv[1].trim());
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if ("v1".equals(kv[0].trim())) {
                signature = kv[1].trim();
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > TOLERANCE.toSeconds()) {
            return false;
        }
        String expected = hmacHex(secret, timestamp + "." + rawBody);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 서명 생성 실패", e);
        }
    }
}

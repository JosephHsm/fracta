package com.fracta.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 사용자에게 나가는 에러 문구의 문체를 고정한다.
 *
 * <p>이 문자열들은 오픈 API 응답의 `error.message`로 외부 개발자에게 그대로 나가고,
 * 개발자 포털 샌드박스 콘솔에도 응답 원문이 표시된다. 즉 화면 문구이므로 합니다체다.
 * 코드 주석·설계 문서에서 쓰는 해라체가 섞여 들어오면 여기서 잡는다
 * (실제로 ErrorCode 27건 + 예외가 덮어쓴 30건이 전부 해라체였다).
 */
class ErrorCodeStyleTest {

    /** {@code ErrorCode.X, "메시지"} 또는 {@code ErrorResponse.of("CODE", "메시지"} 형태만 본다. */
    private static final Pattern MESSAGE_ARGUMENT = Pattern.compile(
            "(?:ErrorCode\\.\\w+\\s*,\\s*"
                    + "|ErrorResponse\\.of\\(\\s*\"[A-Z_]+\"\\s*,\\s*)"
                    + "\"([^\"]{4,})\"");

    /** 끝에 붙은 구두점·공백. 문자열 연결로 값을 덧붙이는 메시지가 있어 떼고 본다. */
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s:.,=]+$");

    @Test
    @DisplayName("모든 기본 메시지는 합니다체다 — 해라체 금지 (FSD §7.1)")
    void allMessagesUsePoliteForm() {
        List<String> violations = Arrays.stream(ErrorCode.values())
                .filter(code -> isPlainForm(code.defaultMessage()))
                .map(code -> code.name() + " → " + code.defaultMessage())
                .toList();

        assertThat(violations)
                .as("해라체 또는 종결어미 누락. 화면에 그대로 나가는 문구다")
                .isEmpty();
    }

    /**
     * 예외가 ErrorCode 기본 메시지를 <b>덮어쓰는</b> 문자열까지 검사한다.
     *
     * <p>enum만 고쳐서는 부족했다. 실제 응답에 나가는 건 대부분 이 덮어쓴 문자열이고,
     * 30건이 해라체였다 — 시드 실행 로그에 `...보다 높다`가 그대로 찍혀 드러났다.
     * 소스를 읽는 테스트는 흔치 않지만, 이 규칙은 빌드 시점 린트로 두는 게 가장 싸다.
     */
    @Test
    @DisplayName("예외가 덮어쓰는 메시지도 합니다체다 — 소스 전수 검사")
    void overriddenMessagesUsePoliteForm() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = MESSAGE_ARGUMENT.matcher(Files.readString(file));
                while (matcher.find()) {
                    String message = matcher.group(1);
                    if (isPlainForm(message)) {
                        violations.add(main.relativize(file) + " → " + message);
                    }
                }
            }
        }

        assertThat(violations)
                .as("해라체 메시지. 오픈 API 응답으로 그대로 나간다")
                .isEmpty();
    }

    @Test
    @DisplayName("접두사 체계를 벗어난 코드가 없다 (FSD §7.1)")
    void allCodesUseKnownPrefix() {
        List<String> allowed = List.of(
                "AUTH_", "VALID_", "STATE_", "FUND_", "RATE_", "IDEM_", "SUIT_",
                "BROKER_", "AI_");

        List<String> violations = Arrays.stream(ErrorCode.values())
                .map(Enum::name)
                .filter(name -> allowed.stream().noneMatch(name::startsWith))
                .toList();

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("판정 규칙 자체가 맞는지 — 해라체는 잡고 합니다체는 통과시킨다")
    void plainFormDetectionIsCorrect() {
        assertThat(isPlainForm("예치금이 부족하다")).isTrue();
        assertThat(isPlainForm("현재 상태: ")).isFalse();
        assertThat(isPlainForm("총 조각 수는 100 이상이어야 한다: ")).isTrue();
        assertThat(isPlainForm("다시 시도하라.")).isTrue();

        assertThat(isPlainForm("예치금이 부족합니다.")).isFalse();
        assertThat(isPlainForm("총 조각 수는 100 이상이어야 합니다: ")).isFalse();
        assertThat(isPlainForm("입금 후 다시 시도해 주세요.")).isFalse();
    }

    /** "…한다"로 끝나면 해라체, "…합니다"로 끝나면 아니다. */
    private static boolean isPlainForm(String message) {
        String trimmed = TRAILING_PUNCTUATION.matcher(message).replaceAll("");
        if (trimmed.contains("하라") || trimmed.contains("해라")) {
            return true;
        }
        return trimmed.endsWith("다") && !trimmed.endsWith("니다");
    }
}

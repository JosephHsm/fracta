package com.fracta.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ErrorCode 기본 메시지의 문체를 고정한다.
 *
 * <p>이 문자열은 오픈 API 응답의 `error.message`로 외부 개발자에게 그대로 나가고,
 * 개발자 포털 샌드박스 콘솔에도 응답 원문이 표시된다. 즉 화면 문구이므로 합니다체다.
 * 코드 주석·설계 문서에서 쓰는 해라체가 섞여 들어오면 여기서 잡는다
 * (실제로 27건 전부 해라체였다).
 */
class ErrorCodeStyleTest {

    @Test
    @DisplayName("모든 기본 메시지는 합니다체다 — 해라체 금지 (FSD §7.1)")
    void allMessagesUsePoliteForm() {
        List<String> violations = Arrays.stream(ErrorCode.values())
                .map(code -> code.name() + " → " + code.defaultMessage())
                .filter(entry -> {
                    String message = entry.substring(entry.indexOf("→ ") + 2);
                    // 평서문은 "~합니다."/"~습니다.", 요청문은 "~해 주세요."로 끝난다
                    return !(message.endsWith("니다.") || message.endsWith("주세요."));
                })
                .toList();

        assertThat(violations)
                .as("해라체 또는 종결어미 누락. 화면에 그대로 나가는 문구다")
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
}

package com.fracta.external.broker.plug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 조회 전용 경로 화이트리스트 — 이 프로젝트에서 실주문을 막는 마지막 방어선이다.
 *
 * <p>접두사 문자열 비교만 하면 {@code ..} 로 빠져나갈 수 있다. 서버가 상대 경로를 풀면
 * 접두사는 맞았는데 실제로는 주문 경로를 부르게 된다.
 */
class PlugPathPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/krstock/quote/inquire-price",
            "/krstock/quote/inquire-daily-price",
            "krstock/quote/inquire-price",           // 선행 슬래시 없음
            "/krstock/quote/inquire-price?iem_cd=005930",
            "/n2/acctinfo",
    })
    @DisplayName("조회 경로는 통과한다")
    void allowsQuotePaths(String path) {
        assertThat(PlugPathPolicy.isAllowed(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/krstock/order/cash",
            "/n2/order",
            "/",
            "/krstock/quotefoo",                     // 접두사가 경계에서 안 맞는다
    })
    @DisplayName("조회가 아닌 경로는 막는다")
    void blocksNonQuotePaths(String path) {
        assertThat(PlugPathPolicy.isAllowed(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/krstock/quote/../../krstock/order/cash",
            "/krstock/quote/../order",
            "/krstock/quote/./../../n2/order",
            "/krstock/quote/%2e%2e/%2e%2e/order",    // 퍼센트 인코딩으로 숨긴 상위 경로
            "/krstock/quote/..\\..\\order",          // 역슬래시 구분자
    })
    @DisplayName("상위 경로로 화이트리스트를 빠져나갈 수 없다")
    void blocksPathTraversal(String path) {
        assertThat(PlugPathPolicy.isAllowed(path))
                .as("조회 전용 화이트리스트를 우회했다: %s", path)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "%zz", "/krstock/quote/%"})
    @DisplayName("판단할 수 없는 경로는 막는다 — 모르는 건 부르지 않는다")
    void blocksUnparseablePaths(String path) {
        assertThat(PlugPathPolicy.isAllowed(path)).isFalse();
    }

    @Test
    @DisplayName("null 도 막는다")
    void blocksNull() {
        assertThat(PlugPathPolicy.isAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("assertReadOnly 는 막힌 경로에서 예외를 던진다")
    void assertReadOnlyThrows() {
        assertThatThrownBy(() -> PlugPathPolicy.assertReadOnly("/krstock/quote/../order"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("조회 전용");

        PlugPathPolicy.assertReadOnly("/krstock/quote/inquire-price");   // 통과
    }
}

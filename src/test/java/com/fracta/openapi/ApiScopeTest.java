package com.fracta.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.presentation.OpenApiIds;

class ApiScopeTest {

    @Test
    @DisplayName("4개 scope 문자열 매핑")
    void values() {
        assertThat(ApiScope.MARKET_READ.value()).isEqualTo("market:read");
        assertThat(ApiScope.ACCOUNT_READ.value()).isEqualTo("account:read");
        assertThat(ApiScope.ORDER_WRITE.value()).isEqualTo("order:write");
        assertThat(ApiScope.SUBSCRIPTION_WRITE.value()).isEqualTo("subscription:write");
    }

    @Test
    @DisplayName("공백 구분 문자열 ↔ 집합 변환")
    void parseAndJoin() {
        Set<ApiScope> scopes = ApiScope.parse("market:read order:write");
        assertThat(scopes).containsExactlyInAnyOrder(ApiScope.MARKET_READ, ApiScope.ORDER_WRITE);
        assertThat(ApiScope.join(scopes)).contains("market:read").contains("order:write");

        assertThat(ApiScope.parse("")).isEmpty();
        assertThat(ApiScope.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("알 수 없는 scope 는 거부한다")
    void unknownScope() {
        assertThatThrownBy(() -> ApiScope.parse("admin:everything"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("식별자는 접두사를 붙여 내보내고 되돌린다 — 내부 PK를 그대로 노출하지 않는다")
    void publicIds() {
        assertThat(OpenApiIds.order(42)).isEqualTo("ord_42");
        assertThat(OpenApiIds.parseOrder("ord_42")).isEqualTo(42);
        assertThat(OpenApiIds.subscription(7)).isEqualTo("sub_7");

        // 다른 자원의 식별자를 잘못 넣으면 걸러진다
        assertThatThrownBy(() -> OpenApiIds.parseOrder("sub_42"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiIds.parseOrder("42"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiIds.parseOrder("ord_abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

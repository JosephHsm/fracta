package com.fracta.external.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.support.IntegrationTestBase;

/**
 * 포트-어댑터 교체 실증 (FSD §0.3, Phase 11 README 항목).
 *
 * <p>"외부 의존은 포트로 추상화한다"는 원칙이 <b>실제로 교체 가능한가</b>를 증명한다.
 * 산문으로 "포트를 썼습니다"라고 적는 것과, 같은 주입 지점이 프로파일에 따라 다른 구현으로
 * 바뀌는 것을 보이는 것은 다르다.
 *
 * <p>교체 방식 — {@code MockMarketDataAdapter}는 항상 등록되고,
 * {@code NamuhPlugMarketDataAdapter}가 {@code @Profile("plug")} + {@code @Primary}로
 * 프로파일이 켜졌을 때만 주입 우선순위를 가져간다. 애플리케이션 코드는 어느 쪽이 붙었는지
 * 알지 못한 채 {@link MarketDataPort} 하나만 본다.
 *
 * <p>여기서는 <b>기본 프로파일</b>만 본다. plug 쪽 주입 검증은
 * {@code PlugAdapterIntegrationTest}에 있다 — WireMock 서버가 정적 필드로 공유되어
 * 같은 기반 클래스를 상속한 클래스가 둘이 되면 포트가 어긋난다.
 */
class MarketDataPortSwapTest {

    @Nested
    @DisplayName("기본(프로파일 없음)")
    class DefaultProfile extends IntegrationTestBase {

        @Autowired
        MarketDataPort marketData;

        @Test
        @DisplayName("MarketDataPort에 Mock 어댑터가 주입된다 — 외부 증권사에 접근하지 않는다")
        void injectsMockAdapter() {
            assertThat(marketData).isInstanceOf(MockMarketDataAdapter.class);

            // 포트 계약이 실제로 동작하는지도 함께 본다
            Quote quote = marketData.getCurrentPrice("MOCK-1000000");
            assertThat(quote.ticker()).isEqualTo("MOCK-1000000");
            assertThat(quote.price().amount()).isPositive();
        }
    }
}

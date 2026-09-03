package com.fracta.external.broker.plug;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.common.money.Money;
import com.fracta.external.broker.MarketDataPort;

class PlugAdapterIntegrationTest extends PlugIntegrationTestBase {

    // 어댑터는 장 시간 외에 마지막 시세를 캐시해 돌려준다(싱글턴). 테스트끼리 캐시를 공유하지 않도록
    // 메서드마다 다른 종목코드를 쓴다.

    @Autowired
    MarketDataPort marketData;

    @Autowired
    PlugTokenManager tokenManager;

    private void stubToken() {
        authServer.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(readStub("token-200.json"))));
    }

    private void stubData(String path, String stubFile) {
        dataServer.stubFor(post(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(readStub(stubFile))));
    }

    @Test
    @DisplayName("현재가 조회 — 실제 캡처 응답에서 stck_prpr 을 Money 로 매핑한다")
    void currentPrice() {
        stubToken();
        stubData("/krstock/quote/v1/currentPrice", "currentPrice-200.json");

        var quote = marketData.getCurrentPrice("005930");

        assertThat(quote.ticker()).isEqualTo("005930");
        assertThat(quote.price()).isEqualTo(Money.of(258_500));   // 캡처된 실제 값
        assertThat(quote.at()).isNotNull();
    }

    @Test
    @DisplayName("토큰 발급은 실전 도메인, 데이터 조회는 모의 도메인으로 분리 호출된다")
    void tokenAndDataUseSeparateDomains() {
        stubToken();
        stubData("/krstock/quote/v1/currentPrice", "currentPrice-200.json");

        marketData.getCurrentPrice("000660");

        // 토큰은 authServer 로만
        authServer.verify(postRequestedFor(urlPathEqualTo("/oauth2/token")));
        assertThat(dataServer.findAll(postRequestedFor(urlPathEqualTo("/oauth2/token")))).isEmpty();

        // 데이터는 dataServer 로만, 규약대로 헤더가 붙는다
        dataServer.verify(postRequestedFor(urlPathEqualTo("/krstock/quote/v1/currentPrice"))
                .withHeader("x-client-id", equalTo("TEST-APP-KEY"))
                .withHeader("x-client-secret", equalTo("TEST-APP-SECRET")));
    }

    @Test
    @DisplayName("일봉 조회 — 구간으로 잘라내고 날짜 오름차순으로 돌려준다")
    void dailyCandles() {
        stubToken();
        stubData("/krstock/quote/v1/period", "period-200.json");

        var candles = marketData.getDailyCandles("005930",
                LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 31));

        // 캡처 응답은 08-25 ~ 08-31 5건. 08-25 는 구간 밖이라 잘린다
        assertThat(candles).hasSize(4);
        assertThat(candles.get(0).date()).isEqualTo(LocalDate.of(2026, 8, 26));
        assertThat(candles.get(3).date()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(candles.get(3).close()).isEqualTo(Money.of(256_750));   // 문자열 값 파싱 확인
        assertThat(candles.get(3).open()).isEqualTo(Money.of(249_000));
        assertThat(candles.get(3).volume()).isEqualTo(11_890_062L);
    }

    @Test
    @DisplayName("IGW40031(잘못된 AppKey) → 업무 오류로 승격")
    void invalidAppKey() {
        stubToken();
        stubData("/krstock/quote/v1/currentPrice", "error-IGW40031.json");

        assertThatThrownBy(() -> marketData.getCurrentPrice("111111"))
                .isInstanceOf(BrokerApiException.class)
                .satisfies(e -> assertThat(((BrokerApiException) e).category())
                        .isEqualTo(BrokerApiException.Category.BUSINESS));
    }

    @Test
    @DisplayName("IGW42901(유량 초과) → RATE_LIMIT, 토큰 재발급하지 않는다")
    void rateLimitDoesNotReissueToken() {
        stubToken();
        stubData("/krstock/quote/v1/currentPrice", "error-IGW42901.json");

        tokenManager.accessToken();                       // 최초 발급을 먼저 끝내고
        long issuedBefore = tokenManager.issueCount();     // 그 이후 증가분만 본다

        assertThatThrownBy(() -> marketData.getCurrentPrice("222222"))
                .isInstanceOf(BrokerApiException.class)
                .satisfies(e -> assertThat(((BrokerApiException) e).category())
                        .isEqualTo(BrokerApiException.Category.RATE_LIMIT));

        // 유량 초과에 재발급하면 상황이 악화된다 — 발급 횟수가 늘지 않아야 한다
        assertThat(tokenManager.issueCount()).isEqualTo(issuedBefore);
    }

    @Test
    @DisplayName("IGW50025(일시 오류) → 업무 오류로 노출되고 재시도는 호출자 몫")
    void transientServerError() {
        stubToken();
        stubData("/krstock/quote/v1/currentPrice", "error-IGW50025.json");

        assertThatThrownBy(() -> marketData.getCurrentPrice("333333"))
                .isInstanceOf(BrokerApiException.class);
        assertThat(PlugErrorCodes.isTransient("IGW50025")).isTrue();
    }

    @Test
    @DisplayName("IGW40401(미지원 URI) → Mock 폴백으로 시세를 계속 제공한다")
    void unsupportedUriFallsBackToMock() {
        stubToken();
        stubData("/krstock/quote/v1/currentPrice", "error-IGW40401.json");

        // Mock 어댑터는 티커의 숫자를 기준가로 쓴다 → 10000 근처 값이 나온다
        var quote = marketData.getCurrentPrice("MOCK-10000");

        assertThat(quote.price().amount()).isBetween(9_800L, 10_200L);
    }

    @Test
    @DisplayName("IGW40023(모의 도메인 미지원) → Mock 폴백. 2026-09-03 실제로 온 응답이다")
    void mockDomainUnsupportedFallsBackToMock() {
        stubToken();
        // 8월 31일에는 정상 응답하던 엔드포인트가 이 코드로 막혔다.
        // 40401만 폴백 대상으로 보고 있어서, 조회가 예외로 떨어지고 있었다.
        stubData("/krstock/quote/v1/currentPrice", "error-IGW40023.json");

        var quote = marketData.getCurrentPrice("MOCK-10000");

        assertThat(quote.price().amount()).isBetween(9_800L, 10_200L);
    }

    @Test
    @DisplayName("포트 교체 실증 — plug 프로파일에서 MarketDataPort가 PLUG 어댑터로 주입된다")
    void marketDataPortSwapsToPlugAdapter() {
        // 호출부 코드는 그대로인데 주입되는 구현만 바뀐다는 것이 포트-어댑터의 요점이다.
        // 기본 프로파일에서 Mock이 주입되는 것은 MarketDataPortSwapTest가 검증한다.
        assertThat(marketData).isInstanceOf(NamuhPlugMarketDataAdapter.class);
    }

}

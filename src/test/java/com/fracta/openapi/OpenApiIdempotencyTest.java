package com.fracta.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.account.api.InvestorId;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.idempotency.IdempotencyService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.OpenApiTestSupport;
import com.fracta.support.TradingTestSupport;

/** 멱등성 (OA-06, FSD §8.5). */
class OpenApiIdempotencyTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OpenApiTestSupport openApi;

    @Autowired
    TradingTestSupport tradingSupport;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdempotencyService idempotency;

    private record Fixture(OpenApiTestSupport.Client client, String tokenSymbol) {
    }

    /** 매도 호가가 깔린 종목과 매수용 오픈 API 클라이언트를 준비한다. */
    private Fixture fixture() {
        var market = tradingSupport.listedMarket(null, 100);
        long seller = tradingSupport.investor(0);
        long buyer = tradingSupport.investor(100_000_000);
        tradingSupport.giveUnits(market, seller, 1_000);

        // 매도 호가를 깔아 매수가 즉시 체결되게 한다
        for (int i = 0; i < 5; i++) {
            tradingSupport.newKey();
        }
        var trading = tradingSupport;
        trading.getClass();   // 명시적 사용 (컴파일러 경고 방지)

        var client = openApi.register(buyer,
                Set.of(ApiScope.ORDER_WRITE, ApiScope.ACCOUNT_READ), ApiEnv.LIVE, 100, 10_000);
        return new Fixture(client, market.tokenSymbol());
    }

    private String orderBody(String symbol, long units) {
        return """
                {"tokenSymbol":"%s","side":"BUY","orderType":"LIMIT","price":1000,"units":%d}
                """.formatted(symbol, units).trim();
    }

    private ResponseEntity<String> postOrder(Fixture fixture, String key, String body) {
        return rest.exchange("/open/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, openApi.bearerWithIdempotency(fixture.client().accessToken(), key)),
                String.class);
    }

    @Test
    @DisplayName("Idempotency-Key 없이 order:write 호출 → 400")
    void missingKeyRejected() throws Exception {
        var fixture = fixture();
        ResponseEntity<String> response = rest.exchange("/open/v1/orders", HttpMethod.POST,
                new HttpEntity<>(orderBody(fixture.tokenSymbol(), 1),
                        openApi.bearer(fixture.client().accessToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).path("error").path("code").asText())
                .isEqualTo("IDEM_KEY_REQUIRED");
    }

    @Test
    @DisplayName("Idempotency-Key 100자 초과 → DB 오류 전 400 거부")
    void oversizedKeyRejected() throws Exception {
        var fixture = fixture();
        ResponseEntity<String> response = postOrder(fixture, "k".repeat(101),
                orderBody(fixture.tokenSymbol(), 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).path("error").path("code").asText())
                .isEqualTo("VALID_INVALID_INPUT");
    }

    @Test
    @DisplayName("동일 키 재요청 → X-Idempotent-Replay: true + 동일 응답")
    void replayReturnsSameResponse() {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();
        String body = orderBody(fixture.tokenSymbol(), 1);

        ResponseEntity<String> first = postOrder(fixture, key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("X-Idempotent-Replay")).isNull();

        ResponseEntity<String> replay = postOrder(fixture, key, body);
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // 주문은 하나만 생겼다
        Long orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE idempotency_key = ?", Long.class, key);
        assertThat(orders).isEqualTo(1);
    }

    @Test
    @DisplayName("PROCESSING 중 같은 요청 → 409 IDEM_IN_PROGRESS")
    void processingRequestReturnsConflict() throws Exception {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();
        String body = orderBody(fixture.tokenSymbol(), 1);
        assertThat(idempotency.begin(fixture.client().clientId(), key,
                "POST", "/open/v1/orders", body)).isEmpty();
        try {
            ResponseEntity<String> response = postOrder(fixture, key, body);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(objectMapper.readTree(response.getBody()).path("error").path("code").asText())
                    .isEqualTo("IDEM_IN_PROGRESS");
        } finally {
            idempotency.abort(fixture.client().clientId(), key);
        }
    }

    @Test
    @DisplayName("주문 취소도 Idempotency-Key 필수이며 완료 응답을 재생한다")
    void cancelIsIdempotent() throws Exception {
        var fixture = fixture();
        ResponseEntity<String> placed = postOrder(fixture, UUID.randomUUID().toString(),
                orderBody(fixture.tokenSymbol(), 1));
        String orderId = objectMapper.readTree(placed.getBody()).path("data").path("orderId").asText();

        ResponseEntity<String> missing = rest.exchange("/open/v1/orders/" + orderId,
                HttpMethod.DELETE, new HttpEntity<>(openApi.bearer(fixture.client().accessToken())),
                String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String cancelKey = UUID.randomUUID().toString();
        ResponseEntity<String> first = rest.exchange("/open/v1/orders/" + orderId,
                HttpMethod.DELETE, new HttpEntity<>(openApi.bearerWithIdempotency(
                        fixture.client().accessToken(), cancelKey)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = rest.exchange("/open/v1/orders/" + orderId,
                HttpMethod.DELETE, new HttpEntity<>(openApi.bearerWithIdempotency(
                        fixture.client().accessToken(), cancelKey)), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        ResponseEntity<String> secondPlaced = postOrder(fixture, UUID.randomUUID().toString(),
                orderBody(fixture.tokenSymbol(), 1));
        String secondOrderId = objectMapper.readTree(secondPlaced.getBody())
                .path("data").path("orderId").asText();
        ResponseEntity<String> wrongResource = rest.exchange("/open/v1/orders/" + secondOrderId,
                HttpMethod.DELETE, new HttpEntity<>(openApi.bearerWithIdempotency(
                        fixture.client().accessToken(), cancelKey)), String.class);
        assertThat(wrongResource.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("같은 키 + 다른 본문 → 422 IDEM_KEY_CONFLICT")
    void bodyMismatchRejected() throws Exception {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();

        assertThat(postOrder(fixture, key, orderBody(fixture.tokenSymbol(), 1)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> conflict = postOrder(fixture, key, orderBody(fixture.tokenSymbol(), 2));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(objectMapper.readTree(conflict.getBody()).path("error").path("code").asText())
                .isEqualTo("IDEM_KEY_CONFLICT");
    }

    @Test
    @DisplayName("본문의 공백·키 순서가 달라도 같은 요청으로 본다 (정규화 해시)")
    void normalizedBodyHash() {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = postOrder(fixture, key, orderBody(fixture.tokenSymbol(), 1));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String reordered = """
                { "units" : 1 , "price" : 1000 , "orderType" : "LIMIT" ,
                  "side" : "BUY" , "tokenSymbol" : "%s" }
                """.formatted(fixture.tokenSymbol());
        ResponseEntity<String> replay = postOrder(fixture, key, reordered);
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
    }

    @Test
    @DisplayName("동시 중복 요청 100건 → 실제 처리 1건 (FSD §14 명시 조건)")
    void concurrentDuplicatesProcessedOnce() throws Exception {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();
        String body = orderBody(fixture.tokenSymbol(), 1);
        int threads = 100;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        java.util.Set<String> otherStatuses = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        ResponseEntity<String> response = postOrder(fixture, key, body);
                        if (response.getStatusCode() == HttpStatus.CREATED) {
                            if ("true".equals(response.getHeaders().getFirst("X-Idempotent-Replay"))) {
                                replayed.incrementAndGet();
                            } else {
                                created.incrementAndGet();
                            }
                        } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                            inProgress.incrementAndGet();   // 처리 중 — 정상 응답이다
                        } else {
                            otherStatuses.add(response.getStatusCode() + ":" + response.getBody());
                        }
                    } catch (Exception e) {
                        otherStatuses.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(otherStatuses).as("예상 밖 응답").isEmpty();
        assertThat(created.get()).as("실제로 처리된 요청").isEqualTo(1);
        assertThat(replayed.get() + inProgress.get()).isEqualTo(threads - 1);

        // DB에도 주문이 딱 하나다
        Long orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE idempotency_key = ?", Long.class, key);
        assertThat(orders).isEqualTo(1);
    }

    @Test
    @DisplayName("처리 중 예외 발생 → PROCESSING 키가 지워져 재시도가 가능하다")
    void failureClearsProcessingKey() {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();
        // 존재하지 않는 종목 → 처리 중 예외
        String badBody = orderBody("FR-NOPE-999", 1);

        ResponseEntity<String> failed = postOrder(fixture, key, badBody);
        assertThat(failed.getStatusCode().is2xxSuccessful()).isFalse();

        // 같은 키로 다시 시도할 수 있어야 한다 (409 로 막히면 24시간 잠긴다)
        ResponseEntity<String> retry = postOrder(fixture, key, orderBody(fixture.tokenSymbol(), 1));
        assertThat(retry.getStatusCode())
                .as("실패 후 같은 키로 재시도가 가능해야 한다")
                .isEqualTo(HttpStatus.CREATED);
    }
}

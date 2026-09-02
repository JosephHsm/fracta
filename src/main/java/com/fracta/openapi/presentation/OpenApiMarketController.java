package com.fracta.openapi.presentation;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.money.Money;
import com.fracta.common.response.ApiResponse;
import com.fracta.external.broker.MarketDataPort;
import com.fracta.external.broker.MockMarketDataAdapter;
import com.fracta.external.broker.PriceConverter;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.gateway.RequiredScope;
import com.fracta.openapi.sandbox.SandboxContext;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 시세 계열 엔드포인트 (FSD §7.2 2~6/12). 전부 {@code market:read} 필요. */
@RestController
@Tag(name = "시세", description = "종목·호가·체결·괴리율 조회")
public class OpenApiMarketController {

    private final ListedTokenPort listedTokens;
    private final TradingService trading;
    private final MarketDataPort marketData;
    private final MockMarketDataAdapter mockMarketData;

    public OpenApiMarketController(ListedTokenPort listedTokens, TradingService trading,
                                   MarketDataPort marketData,
                                   MockMarketDataAdapter mockMarketData) {
        this.listedTokens = listedTokens;
        this.trading = trading;
        this.marketData = marketData;
        this.mockMarketData = mockMarketData;
    }

    /**
     * 공개 API 응답 계약. 파트너 개발자가 Swagger에서 바로 읽을 수 있어야 하므로
     * Map으로 두지 않는다 — 스키마가 비어 있는 오픈 API 문서는 문서가 아니다.
     */
    public record TokenSummaryResponse(String tokenSymbol, String status, boolean tradable,
                                       String brokerTicker, long splitRatio, int riskGrade) {
    }

    public record OrderBookResponse(String tokenSymbol, List<Level> bids, List<Level> asks) {

        public record Level(long price, long units) {
        }
    }

    public record ExecutionResponse(Long executionId, long price, long units,
                                    BigDecimal premiumRate, String executedAt) {
    }

    /** 괴리율. 원자산 시세를 못 구하면 referencePrice·premiumRate가 null이다. */
    public record PremiumResponse(String tokenSymbol, Long lastExecutedPrice, Long referencePrice,
                                  BigDecimal premiumRate) {
    }

    @Operation(summary = "상장 종목 목록", description = "거래 가능한 종목을 돌려준다. 예: GET /open/v1/tokens")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens", "/open/sandbox/v1/tokens"})
    public ApiResponse<List<TokenSummaryResponse>> tokens() {
        return ApiResponse.of(listedTokens.listAll().stream().map(this::summary).toList());
    }

    @Operation(summary = "종목 상세", description = "발행 정보와 기초자산. 예: GET /open/v1/tokens/FR-ESRK-001")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}", "/open/sandbox/v1/tokens/{symbol}"})
    public ApiResponse<TokenSummaryResponse> token(@PathVariable("symbol") String symbol) {
        return ApiResponse.of(summary(require(symbol)));
    }

    @Operation(summary = "10호가", description = "매수·매도 각 10호가. 예: GET /open/v1/tokens/FR-ESRK-001/orderbook")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}/orderbook", "/open/sandbox/v1/tokens/{symbol}/orderbook"})
    public ApiResponse<OrderBookResponse> orderbook(@PathVariable("symbol") String symbol,
                                                    @RequestParam(value = "levels", defaultValue = "10")
                                                    int levels) {
        require(symbol);
        return ApiResponse.of(new OrderBookResponse(symbol,
                levels(symbol, OrderSide.BUY, levels), levels(symbol, OrderSide.SELL, levels)));
    }

    @Operation(summary = "체결 내역", description = "최신순 페이징. 예: GET /open/v1/tokens/FR-ESRK-001/executions?page=0&size=20")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}/executions", "/open/sandbox/v1/tokens/{symbol}/executions"})
    public ApiResponse<List<ExecutionResponse>> executions(
            @PathVariable("symbol") String symbol,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        require(symbol);
        var list = trading.executions(symbol, PageRequest.of(page, size)).stream()
                .map(e -> new ExecutionResponse(e.id(), e.price(), e.units(), e.premiumRate(),
                        e.executedAt().toString()))
                .toList();
        return ApiResponse.of(list);
    }

    @Operation(summary = "괴리율",
            description = "원자산 시세 대비 최근 체결가의 괴리율. 시세를 못 구하면 null. "
                    + "예: GET /open/v1/tokens/FR-ESRK-001/premium")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}/premium", "/open/sandbox/v1/tokens/{symbol}/premium"})
    public ApiResponse<PremiumResponse> premium(@PathVariable("symbol") String symbol) {
        var token = require(symbol);

        var latest = trading.executions(symbol, PageRequest.of(0, 1)).stream().findFirst();
        Long lastExecutedPrice = latest.map(e -> e.price()).orElse(null);

        BigDecimal premiumRate = null;
        Long referencePrice = null;
        if (latest.isPresent() && token.brokerTicker() != null && !token.brokerTicker().isBlank()) {
            try {
                // plug 프로필에서도 샌드박스는 외부 증권사에 접근하지 않고 가상 시세만 쓴다.
                MarketDataPort source = SandboxContext.isSandbox() ? mockMarketData : marketData;
                Money underlying = source.getCurrentPrice(token.brokerTicker()).price();
                Money reference = PriceConverter.referencePrice(underlying, token.splitRatio());
                referencePrice = reference.amount();
                premiumRate = PriceConverter
                        .premiumRate(Money.of(latest.get().price()), reference).orElse(null);
            } catch (RuntimeException e) {
                // 시세 조회 실패 시 괴리율만 비운다 — 조회 자체는 성공시킨다
                premiumRate = null;
            }
        }
        return ApiResponse.of(new PremiumResponse(symbol, lastExecutedPrice, referencePrice,
                premiumRate));
    }

    private ListedTokenPort.ListedToken require(String symbol) {
        return listedTokens.findByTokenSymbol(symbol)
                .orElseThrow(() -> new IllegalArgumentException("종목이 없다: " + symbol));
    }

    private TokenSummaryResponse summary(ListedTokenPort.ListedToken token) {
        return new TokenSummaryResponse(token.tokenSymbol(), token.status(), token.tradable(),
                token.brokerTicker(), token.splitRatio(), token.riskGrade());
    }

    /** 도메인 오더북 타입을 공개 계약으로 옮긴다 — 내부 record가 파트너 스펙에 새지 않게. */
    private List<OrderBookResponse.Level> levels(String symbol, OrderSide side, int depth) {
        return trading.depth(symbol, side, depth).stream()
                .map(level -> new OrderBookResponse.Level(level.price(), level.units()))
                .toList();
    }
}

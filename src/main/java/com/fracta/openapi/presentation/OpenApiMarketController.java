package com.fracta.openapi.presentation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Operation(summary = "상장 종목 목록", description = "거래 가능한 종목을 돌려준다. 예: GET /open/v1/tokens")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens", "/open/sandbox/v1/tokens"})
    public ApiResponse<List<Map<String, Object>>> tokens() {
        return ApiResponse.of(listedTokens.listAll().stream().map(this::summary).toList());
    }

    @Operation(summary = "종목 상세", description = "발행 정보와 기초자산. 예: GET /open/v1/tokens/FR-ESRK-001")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}", "/open/sandbox/v1/tokens/{symbol}"})
    public ApiResponse<Map<String, Object>> token(@PathVariable("symbol") String symbol) {
        return ApiResponse.of(summary(require(symbol)));
    }

    @Operation(summary = "10호가", description = "매수·매도 각 10호가. 예: GET /open/v1/tokens/FR-ESRK-001/orderbook")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}/orderbook", "/open/sandbox/v1/tokens/{symbol}/orderbook"})
    public ApiResponse<Map<String, Object>> orderbook(@PathVariable("symbol") String symbol,
                                                      @RequestParam(value = "levels", defaultValue = "10")
                                                      int levels) {
        require(symbol);
        Map<String, Object> body = new HashMap<>();
        body.put("tokenSymbol", symbol);
        body.put("bids", trading.depth(symbol, OrderSide.BUY, levels));
        body.put("asks", trading.depth(symbol, OrderSide.SELL, levels));
        return ApiResponse.of(body);
    }

    @Operation(summary = "체결 내역", description = "최신순 페이징. 예: GET /open/v1/tokens/FR-ESRK-001/executions?page=0&size=20")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}/executions", "/open/sandbox/v1/tokens/{symbol}/executions"})
    public ApiResponse<List<Map<String, Object>>> executions(
            @PathVariable("symbol") String symbol,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        require(symbol);
        var list = trading.executions(symbol, PageRequest.of(page, size)).stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("executionId", e.id());
                    m.put("price", e.price());
                    m.put("units", e.units());
                    m.put("premiumRate", e.premiumRate());
                    m.put("executedAt", e.executedAt().toString());
                    return m;
                })
                .toList();
        return ApiResponse.of(list);
    }

    @Operation(summary = "괴리율",
            description = "원자산 시세 대비 최근 체결가의 괴리율. 시세를 못 구하면 null. "
                    + "예: GET /open/v1/tokens/FR-ESRK-001/premium")
    @RequiredScope(ApiScope.MARKET_READ)
    @GetMapping({"/open/v1/tokens/{symbol}/premium", "/open/sandbox/v1/tokens/{symbol}/premium"})
    public ApiResponse<Map<String, Object>> premium(@PathVariable("symbol") String symbol) {
        var token = require(symbol);
        Map<String, Object> body = new HashMap<>();
        body.put("tokenSymbol", symbol);

        var latest = trading.executions(symbol, PageRequest.of(0, 1)).stream().findFirst();
        body.put("lastExecutedPrice", latest.map(e -> e.price()).orElse(null));

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
        body.put("referencePrice", referencePrice);
        body.put("premiumRate", premiumRate);
        return ApiResponse.of(body);
    }

    private ListedTokenPort.ListedToken require(String symbol) {
        return listedTokens.findByTokenSymbol(symbol)
                .orElseThrow(() -> new IllegalArgumentException("종목이 없다: " + symbol));
    }

    private Map<String, Object> summary(ListedTokenPort.ListedToken token) {
        Map<String, Object> m = new HashMap<>();
        m.put("tokenSymbol", token.tokenSymbol());
        m.put("status", token.status());
        m.put("tradable", token.tradable());
        m.put("brokerTicker", token.brokerTicker());
        m.put("splitRatio", token.splitRatio());
        m.put("riskGrade", token.riskGrade());
        return m;
    }
}

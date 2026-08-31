package com.fracta.trading.presentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.account.api.InvestorId;
import com.fracta.common.response.ApiResponse;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 유통 REST API. Phase 7에서 오픈 API로 그대로 노출할 수 있는 형태로 둔다. */
@RestController
public class TradingController {

    public record PlaceOrderRequest(@NotNull OrderSide side,
                                    @NotNull OrderType orderType,
                                    Long price,
                                    @NotNull @Positive Long units,
                                    String idempotencyKey) {
    }

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/api/v1/tokens/{tokenSymbol}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TradingService.PlaceResult> place(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("tokenSymbol") String tokenSymbol,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody PlaceOrderRequest request) {
        String key = firstNonBlank(idempotencyHeader, request.idempotencyKey(),
                UUID.randomUUID().toString());
        return ApiResponse.of(tradingService.place(tokenSymbol, investorIdOf(jwt), request.side(),
                request.orderType(), request.price(), request.units(), key));
    }

    @DeleteMapping("/api/v1/orders/{orderId}")
    public ApiResponse<TradingService.PlaceResult> cancel(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable("orderId") long orderId) {
        return ApiResponse.of(tradingService.cancel(orderId, investorIdOf(jwt)));
    }

    @GetMapping("/api/v1/orders/me")
    public ApiResponse<List<Map<String, Object>>> myOrders(@AuthenticationPrincipal Jwt jwt) {
        var list = tradingService.ordersOf(investorIdOf(jwt)).stream()
                .map(o -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("orderId", o.id());
                    m.put("tokenSymbol", o.tokenSymbol());
                    m.put("side", o.side().name());
                    m.put("orderType", o.orderType().name());
                    m.put("price", o.price());
                    m.put("units", o.units());
                    m.put("filledUnits", o.filledUnits());
                    m.put("status", o.status().name());
                    return m;
                })
                .toList();
        return ApiResponse.of(list);
    }

    /** 호가창 10호가 (TR-05). */
    @GetMapping("/api/v1/tokens/{tokenSymbol}/orderbook")
    public ApiResponse<Map<String, Object>> orderBook(@PathVariable("tokenSymbol") String tokenSymbol,
                                                      @RequestParam(value = "levels", defaultValue = "10")
                                                      int levels) {
        Map<String, Object> body = new HashMap<>();
        body.put("tokenSymbol", tokenSymbol);
        body.put("bids", tradingService.depth(tokenSymbol, OrderSide.BUY, levels));
        body.put("asks", tradingService.depth(tokenSymbol, OrderSide.SELL, levels));
        return ApiResponse.of(body);
    }

    /** 체결 내역 페이징 (TR-06). */
    @GetMapping("/api/v1/tokens/{tokenSymbol}/executions")
    public ApiResponse<List<Map<String, Object>>> executions(
            @PathVariable("tokenSymbol") String tokenSymbol,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        var list = tradingService.executions(tokenSymbol, PageRequest.of(page, size)).stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("executionId", e.id());
                    m.put("price", e.price());
                    m.put("units", e.units());
                    m.put("buyFee", e.buyFee());
                    m.put("sellFee", e.sellFee());
                    m.put("premiumRate", e.premiumRate());
                    m.put("executedAt", e.executedAt().toString());
                    return m;
                })
                .toList();
        return ApiResponse.of(list);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return UUID.randomUUID().toString();
    }

    private InvestorId investorIdOf(Jwt jwt) {
        return InvestorId.of(Long.parseLong(jwt.getSubject()));
    }
}

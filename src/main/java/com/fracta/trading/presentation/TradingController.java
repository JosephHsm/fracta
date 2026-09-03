package com.fracta.trading.presentation;

import java.math.BigDecimal;
import java.util.List;
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
import com.fracta.trading.application.MarketChartService;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.OrderType;
import com.fracta.trading.domain.TradingExceptions;

import io.swagger.v3.oas.annotations.Operation;
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

    /**
     * 아래 응답 record들은 기존 {@code Map<String,Object>} 응답을 <b>같은 필드명·같은 JSON</b>으로
     * 옮긴 것이다. 스펙에 타입이 실려야 프론트의 생성 클라이언트가 필드 변경을 컴파일 시점에
     * 잡아낸다(FSD §11.1). Map으로 두면 필드명을 바꿔도 프론트는 런타임에 undefined가 될 뿐이다.
     */
    public record MyOrderResponse(Long orderId, String tokenSymbol, OrderSide side,
                                  OrderType orderType, Long price, long units, long filledUnits,
                                  OrderStatus status) {
    }

    /** 차트 조회 범위. 무제한으로 열면 증권사 쿼터를 그대로 소모한다. */
    private static final int MIN_CANDLE_DAYS = 1;
    private static final int MAX_CANDLE_DAYS = 365;

    /**
     * 기초자산 일봉. 금액은 전부 <b>조각 참조가</b>(원)다 — 화면이 다시 계산하지 않는다.
     * 증권사 티커가 없거나 시세 조회가 실패하면 {@code candles}가 빈 목록이다. 오류가 아니다.
     */
    public record CandlesResponse(String tokenSymbol, String brokerTicker, long splitRatio,
                                  List<CandleResponse> candles) {
    }

    /** 하루치 봉. {@code date}는 ISO-8601 문자열(yyyy-MM-dd)이다. */
    public record CandleResponse(String date, long open, long high, long low, long close,
                                 long volume) {
    }

    /** 호가창 10호가 (TR-05). */
    public record OrderBookResponse(String tokenSymbol, List<OrderBookLevel> bids,
                                    List<OrderBookLevel> asks) {
    }

    /** 한 호가 단계. 오픈 API의 동명 타입과 스키마 이름이 겹치지 않게 분리한다. */
    public record OrderBookLevel(long price, long units) {
    }

    /** 체결 내역 한 건 (TR-06). */
    public record ExecutionResponse(Long executionId, long price, long units, long buyFee,
                                    long sellFee, BigDecimal premiumRate, String executedAt) {
    }

    private final TradingService tradingService;
    private final MarketChartService marketChart;

    public TradingController(TradingService tradingService, MarketChartService marketChart) {
        this.tradingService = tradingService;
        this.marketChart = marketChart;
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

    @Operation(operationId = "cancelMyOrder", summary = "주문 취소")
    @DeleteMapping("/api/v1/orders/{orderId}")
    public ApiResponse<TradingService.PlaceResult> cancel(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable("orderId") long orderId) {
        return ApiResponse.of(tradingService.cancel(orderId, investorIdOf(jwt)));
    }

    @Operation(operationId = "listMyOrders", summary = "내 주문 내역")
    @GetMapping("/api/v1/orders/me")
    public ApiResponse<List<MyOrderResponse>> myOrders(@AuthenticationPrincipal Jwt jwt) {
        var list = tradingService.ordersOf(investorIdOf(jwt)).stream()
                .map(o -> new MyOrderResponse(o.id(), o.tokenSymbol(), o.side(), o.orderType(),
                        o.price(), o.units(), o.filledUnits(), o.status()))
                .toList();
        return ApiResponse.of(list);
    }

    /** 호가창 10호가 (TR-05). */
    @GetMapping("/api/v1/tokens/{tokenSymbol}/orderbook")
    public ApiResponse<OrderBookResponse> orderBook(@PathVariable("tokenSymbol") String tokenSymbol,
                                                    @RequestParam(value = "levels", defaultValue = "10")
                                                    int levels) {
        return ApiResponse.of(new OrderBookResponse(tokenSymbol,
                levels(tokenSymbol, OrderSide.BUY, levels),
                levels(tokenSymbol, OrderSide.SELL, levels)));
    }

    /** 체결 내역 페이징 (TR-06). */
    @Operation(operationId = "listExecutions", summary = "체결 내역")
    @GetMapping("/api/v1/tokens/{tokenSymbol}/executions")
    public ApiResponse<List<ExecutionResponse>> executions(
            @PathVariable("tokenSymbol") String tokenSymbol,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        var list = tradingService.executions(tokenSymbol, PageRequest.of(page, size)).stream()
                .map(e -> new ExecutionResponse(e.id(), e.price(), e.units(), e.buyFee(),
                        e.sellFee(), e.premiumRate(), e.executedAt().toString()))
                .toList();
        return ApiResponse.of(list);
    }

    /**
     * 기초자산 시세 차트용 일봉 (FSD §11 종목 상세 필수 요소).
     *
     * <p>값은 <b>조각 참조가로 환산해서</b> 내려간다. 원자산 가격을 그대로 주면 화면이
     * 분할비율로 나눠야 하는데, 프론트의 금액 재계산은 금지다(FSD §11.4).
     */
    @Operation(operationId = "listCandles", summary = "기초자산 일봉 (조각 참조가 환산)")
    @GetMapping("/api/v1/tokens/{tokenSymbol}/candles")
    public ApiResponse<CandlesResponse> candles(
            @PathVariable("tokenSymbol") String tokenSymbol,
            @RequestParam(value = "days", defaultValue = "90") int days) {
        if (days < MIN_CANDLE_DAYS || days > MAX_CANDLE_DAYS) {
            throw new TradingExceptions.InvalidCandleRangeException(
                    days, MIN_CANDLE_DAYS, MAX_CANDLE_DAYS);
        }
        var chart = marketChart.dailyChart(tokenSymbol, days);
        return ApiResponse.of(new CandlesResponse(chart.tokenSymbol(), chart.brokerTicker(),
                chart.splitRatio(),
                chart.candles().stream()
                        .map(c -> new CandleResponse(c.date().toString(), c.open(), c.high(),
                                c.low(), c.close(), c.volume()))
                        .toList()));
    }

    /** 오더북 도메인 타입을 응답 계약으로 옮긴다 — 도메인 record가 그대로 스펙에 새지 않게 한다. */
    private List<OrderBookLevel> levels(String tokenSymbol, OrderSide side, int depth) {
        return tradingService.depth(tokenSymbol, side, depth).stream()
                .map(level -> new OrderBookLevel(level.price(), level.units()))
                .toList();
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

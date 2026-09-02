package com.fracta.openapi.presentation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.account.api.InvestorId;
import com.fracta.common.response.ApiResponse;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.gateway.OpenApiContext;
import com.fracta.openapi.gateway.RequiredScope;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.trading.application.TradingService;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 주문·청약 엔드포인트 (FSD §7.2 9~11/12). 전부 멱등성 키가 필수다. */
@RestController
@Tag(name = "거래", description = "주문·청약 (Idempotency-Key 필수)")
public class OpenApiTradingController {

    public record OpenApiPlaceOrderRequest(@NotBlank String tokenSymbol,
                                    @NotNull OrderSide side,
                                    @NotNull OrderType orderType,
                                    Long price,
                                    @NotNull @Positive Long units) {
    }

    public record SubscribeRequest(@NotNull Long issuanceId, @NotNull @Positive Long units) {
    }

    private final TradingService trading;
    private final SubscriptionService subscriptions;

    public OpenApiTradingController(TradingService trading, SubscriptionService subscriptions) {
        this.trading = trading;
        this.subscriptions = subscriptions;
    }

    /** 주문 접수 결과. 즉시 체결된 건은 executions에 요약이 실린다. */
    public record PlaceOrderResponse(String orderId, String status, long filledUnits,
                                     long remainingUnits, List<ExecutionSummary> executions) {

        public record ExecutionSummary(long price, long units, String executedAt) {
        }
    }

    public record CancelOrderResponse(String orderId, String status, long filledUnits) {
    }

    public record SubscribeResponse(String subscriptionId, long requestedUnits, long depositAmount,
                                    String status) {
    }

    @Operation(summary = "주문",
            description = "지정가/시장가 주문. `Idempotency-Key` 헤더 필수. "
                    + "예: {\"tokenSymbol\":\"FR-ESRK-001\",\"side\":\"BUY\","
                    + "\"orderType\":\"LIMIT\",\"price\":4200,\"units\":100}")
    @RequiredScope(value = ApiScope.ORDER_WRITE, idempotent = true)
    @PostMapping({"/open/v1/orders", "/open/sandbox/v1/orders"})
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PlaceOrderResponse> placeOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OpenApiPlaceOrderRequest request) {
        InvestorId investor = InvestorId.of(OpenApiContext.current().ownerInvestorId());
        var result = trading.place(request.tokenSymbol(), investor, request.side(),
                request.orderType(), request.price(), request.units(), idempotencyKey);

        return ApiResponse.of(new PlaceOrderResponse(OpenApiIds.order(result.orderId()),
                result.status(), result.filledUnits(),
                request.units() - result.filledUnits(),
                executionSummaries(request.tokenSymbol(), result.executionIds())));
    }

    @Operation(summary = "주문 취소",
            description = "미체결 잔량만 취소된다. 예: DELETE /open/v1/orders/ord_42")
    @RequiredScope(value = ApiScope.ORDER_WRITE, idempotent = true)
    @DeleteMapping({"/open/v1/orders/{orderId}", "/open/sandbox/v1/orders/{orderId}"})
    public ApiResponse<CancelOrderResponse> cancelOrder(@PathVariable("orderId") String orderId) {
        InvestorId investor = InvestorId.of(OpenApiContext.current().ownerInvestorId());
        var result = trading.cancel(OpenApiIds.parseOrder(orderId), investor);

        return ApiResponse.of(new CancelOrderResponse(OpenApiIds.order(result.orderId()),
                result.status(), result.filledUnits()));
    }

    @Operation(summary = "청약",
            description = "증거금이 예치금에서 차감된다. `Idempotency-Key` 헤더 필수. "
                    + "예: {\"issuanceId\":1,\"units\":10}")
    @RequiredScope(value = ApiScope.SUBSCRIPTION_WRITE, idempotent = true)
    @PostMapping({"/open/v1/subscriptions", "/open/sandbox/v1/subscriptions"})
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubscribeResponse> subscribe(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubscribeRequest request) {
        InvestorId investor = InvestorId.of(OpenApiContext.current().ownerInvestorId());
        var result = subscriptions.apply(request.issuanceId(), investor,
                request.units(), idempotencyKey);

        return ApiResponse.of(new SubscribeResponse(
                OpenApiIds.subscription(result.orderId()), result.requestedUnits(),
                result.depositAmount(), result.status()));
    }

    private List<PlaceOrderResponse.ExecutionSummary> executionSummaries(String symbol,
                                                                        List<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return List.of();
        }
        return trading.executions(symbol, org.springframework.data.domain.PageRequest.of(0, 50))
                .stream()
                .filter(e -> executionIds.contains(e.id()))
                .map(e -> new PlaceOrderResponse.ExecutionSummary(e.price(), e.units(),
                        e.executedAt().toString()))
                .toList();
    }
}

package com.fracta.subscription.presentation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.account.api.InvestorId;
import com.fracta.common.response.ApiResponse;
import com.fracta.subscription.application.SubscriptionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
public class SubscriptionController {

    public record ApplyRequest(@NotNull @Positive Long units,
                               @Size(max = 100) String idempotencyKey) {
    }

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/api/v1/issuances/{issuanceId}/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubscriptionService.ApplyResult> apply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("issuanceId") long issuanceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody ApplyRequest request) {
        String key = idempotencyHeader != null && !idempotencyHeader.isBlank() ? idempotencyHeader
                : request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                        ? request.idempotencyKey()
                        : UUID.randomUUID().toString();
        return ApiResponse.of(subscriptionService.apply(
                issuanceId, investorIdOf(jwt), request.units(), key));
    }

    @DeleteMapping("/api/v1/subscriptions/{orderId}")
    public ApiResponse<SubscriptionService.ApplyResult> cancel(@AuthenticationPrincipal Jwt jwt,
                                                               @PathVariable("orderId") long orderId) {
        return ApiResponse.of(subscriptionService.cancel(orderId, investorIdOf(jwt)));
    }

    @GetMapping("/api/v1/subscriptions/me")
    public ApiResponse<List<Map<String, Object>>> mine(@AuthenticationPrincipal Jwt jwt) {
        var list = subscriptionService.ordersOf(investorIdOf(jwt)).stream()
                .map(o -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("orderId", o.id());
                    m.put("issuanceId", o.issuanceId());
                    m.put("requestedUnits", o.requestedUnits());
                    m.put("allottedUnits", o.allottedUnits());
                    m.put("depositAmount", o.depositAmount());
                    m.put("status", o.status().name());
                    return m;
                })
                .toList();
        return ApiResponse.of(list);
    }

    private InvestorId investorIdOf(Jwt jwt) {
        return InvestorId.of(Long.parseLong(jwt.getSubject()));
    }
}

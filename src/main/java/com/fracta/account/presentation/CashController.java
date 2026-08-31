package com.fracta.account.presentation;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.account.application.CashService;
import com.fracta.common.money.Money;
import com.fracta.common.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/v1/investors/me/cash")
public class CashController {

    public record CashRequest(@NotNull @Positive Long amount) {
    }

    private final CashService cashService;

    public CashController(CashService cashService) {
        this.cashService = cashService;
    }

    @PostMapping("/deposit")
    public ApiResponse<Map<String, Object>> deposit(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody CashRequest request) {
        Money balance = cashService.deposit(InvestorController.investorIdOf(jwt), Money.of(request.amount()));
        return ApiResponse.of(Map.of("balance", balance.toDisplay()));
    }

    @PostMapping("/withdrawal")
    public ApiResponse<Map<String, Object>> withdraw(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody CashRequest request) {
        Money balance = cashService.withdraw(InvestorController.investorIdOf(jwt), Money.of(request.amount()));
        return ApiResponse.of(Map.of("balance", balance.toDisplay()));
    }

    @GetMapping("/transactions")
    public ApiResponse<List<Map<String, Object>>> transactions(@AuthenticationPrincipal Jwt jwt) {
        var list = cashService.transactionsOf(InvestorController.investorIdOf(jwt)).stream()
                .map(tx -> Map.<String, Object>of(
                        "type", tx.txType().name(),
                        "amount", tx.amount(),
                        "balanceAfter", tx.balanceAfter()))
                .toList();
        return ApiResponse.of(list);
    }
}

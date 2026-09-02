package com.fracta.account.presentation;

import java.math.BigDecimal;
import java.util.List;

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

    /** 입출금 후 잔액. 금액은 서버가 계산한 값을 그대로 내보낸다 — 프론트는 재계산하지 않는다. */
    public record BalanceResponse(BigDecimal balance) {
    }

    public record CashTransactionResponse(String type, long amount, long balanceAfter) {
    }

    private final CashService cashService;

    public CashController(CashService cashService) {
        this.cashService = cashService;
    }

    @PostMapping("/deposit")
    public ApiResponse<BalanceResponse> deposit(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody CashRequest request) {
        Money balance = cashService.deposit(InvestorController.investorIdOf(jwt), Money.of(request.amount()));
        return ApiResponse.of(new BalanceResponse(balance.toDisplay()));
    }

    @PostMapping("/withdrawal")
    public ApiResponse<BalanceResponse> withdraw(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody CashRequest request) {
        Money balance = cashService.withdraw(InvestorController.investorIdOf(jwt), Money.of(request.amount()));
        return ApiResponse.of(new BalanceResponse(balance.toDisplay()));
    }

    @GetMapping("/transactions")
    public ApiResponse<List<CashTransactionResponse>> transactions(@AuthenticationPrincipal Jwt jwt) {
        var list = cashService.transactionsOf(InvestorController.investorIdOf(jwt)).stream()
                .map(tx -> new CashTransactionResponse(tx.txType().name(), tx.amount(),
                        tx.balanceAfter()))
                .toList();
        return ApiResponse.of(list);
    }
}

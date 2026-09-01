package com.fracta.openapi.presentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InvestorId;
import com.fracta.common.response.ApiResponse;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.openapi.auth.ApiScope;
import com.fracta.openapi.gateway.OpenApiContext;
import com.fracta.openapi.gateway.RequiredScope;
import com.fracta.trading.application.TradingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 계좌 계열 엔드포인트 (FSD §7.2 7~8/12). {@code account:read} 필요. */
@RestController
@Tag(name = "계좌", description = "잔고·주문 내역 조회")
public class OpenApiAccountController {

    private final AccountQueryPort accounts;
    private final LedgerPort ledger;
    private final ListedTokenPort listedTokens;
    private final TradingService trading;

    public OpenApiAccountController(AccountQueryPort accounts, LedgerPort ledger,
                                    ListedTokenPort listedTokens, TradingService trading) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.listedTokens = listedTokens;
        this.trading = trading;
    }

    @Operation(summary = "잔고 조회",
            description = "예치금과 보유 조각 수량. 클라이언트 소유자 계정 기준. 예: GET /open/v1/accounts/balance")
    @RequiredScope(ApiScope.ACCOUNT_READ)
    @GetMapping({"/open/v1/accounts/balance", "/open/sandbox/v1/accounts/balance"})
    public ApiResponse<Map<String, Object>> balance() {
        InvestorId investor = InvestorId.of(OpenApiContext.current().ownerInvestorId());

        List<Map<String, Object>> holdings = listedTokens.listAll().stream()
                .map(token -> {
                    var balance = ledger.balanceOf(token.tokenSymbol(),
                            OwnerId.of(investor.value()));
                    Map<String, Object> m = new HashMap<>();
                    m.put("tokenSymbol", token.tokenSymbol());
                    m.put("units", balance.units().value());
                    m.put("lockedUnits", balance.lockedUnits().value());
                    m.put("availableUnits", balance.available().value());
                    return m;
                })
                .filter(m -> ((Long) m.get("units")) > 0)
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("investorId", investor.value());
        body.put("cashBalance", accounts.cashBalanceOf(investor).toDisplay());
        body.put("holdings", holdings);
        return ApiResponse.of(body);
    }

    @Operation(summary = "주문 내역", description = "최신순. 예: GET /open/v1/accounts/orders")
    @RequiredScope(ApiScope.ACCOUNT_READ)
    @GetMapping({"/open/v1/accounts/orders", "/open/sandbox/v1/accounts/orders"})
    public ApiResponse<List<Map<String, Object>>> orders() {
        InvestorId investor = InvestorId.of(OpenApiContext.current().ownerInvestorId());
        var list = trading.ordersOf(investor).stream()
                .map(o -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("orderId", OpenApiIds.order(o.id()));
                    m.put("tokenSymbol", o.tokenSymbol());
                    m.put("side", o.side().name());
                    m.put("orderType", o.orderType().name());
                    m.put("price", o.price());
                    m.put("units", o.units());
                    m.put("filledUnits", o.filledUnits());
                    m.put("remainingUnits", o.remaining());
                    m.put("status", o.status().name());
                    return m;
                })
                .toList();
        return ApiResponse.of(list);
    }
}

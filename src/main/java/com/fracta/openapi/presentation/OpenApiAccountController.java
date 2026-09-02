package com.fracta.openapi.presentation;

import java.math.BigDecimal;
import java.util.List;

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

    /** 공개 API 잔고 응답. 보유가 0인 종목은 목록에서 제외한다. */
    public record BalanceResponse(long investorId, BigDecimal cashBalance, List<Holding> holdings) {

        public record Holding(String tokenSymbol, long units, long lockedUnits, long availableUnits) {
        }
    }

    /** 공개 API 주문 내역. orderId는 내부 PK가 아니라 외부 노출용 식별자다. */
    public record OrderResponse(String orderId, String tokenSymbol, String side, String orderType,
                                Long price, long units, long filledUnits, long remainingUnits,
                                String status) {
    }

    @Operation(summary = "잔고 조회",
            description = "예치금과 보유 조각 수량. 클라이언트 소유자 계정 기준. 예: GET /open/v1/accounts/balance")
    @RequiredScope(ApiScope.ACCOUNT_READ)
    @GetMapping({"/open/v1/accounts/balance", "/open/sandbox/v1/accounts/balance"})
    public ApiResponse<BalanceResponse> balance() {
        InvestorId investor = InvestorId.of(OpenApiContext.current().ownerInvestorId());

        List<BalanceResponse.Holding> holdings = listedTokens.listAll().stream()
                .map(token -> {
                    var balance = ledger.balanceOf(token.tokenSymbol(),
                            OwnerId.of(investor.value()));
                    return new BalanceResponse.Holding(token.tokenSymbol(),
                            balance.units().value(), balance.lockedUnits().value(),
                            balance.available().value());
                })
                .filter(holding -> holding.units() > 0)
                .toList();

        return ApiResponse.of(new BalanceResponse(investor.value(),
                accounts.cashBalanceOf(investor).toDisplay(), holdings));
    }

    @Operation(summary = "주문 내역", description = "최신순. 예: GET /open/v1/accounts/orders")
    @RequiredScope(ApiScope.ACCOUNT_READ)
    @GetMapping({"/open/v1/accounts/orders", "/open/sandbox/v1/accounts/orders"})
    public ApiResponse<List<OrderResponse>> orders() {
        InvestorId investor = InvestorId.of(OpenApiContext.current().ownerInvestorId());
        var list = trading.ordersOf(investor).stream()
                .map(o -> new OrderResponse(OpenApiIds.order(o.id()), o.tokenSymbol(),
                        o.side().name(), o.orderType().name(), o.price(), o.units(),
                        o.filledUnits(), o.remaining(), o.status().name()))
                .toList();
        return ApiResponse.of(list);
    }
}

package com.fracta.support;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fracta.account.api.InvestorId;
import com.fracta.account.application.AuthService;
import com.fracta.account.application.CashService;
import com.fracta.account.application.RiskProfileService;
import com.fracta.common.money.Money;
import com.fracta.common.money.Units;
import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;

/** 유통 테스트 픽스처 — LISTED 종목과 보유·현금을 갖춘 투자자를 만든다. */
@Component
public class TradingTestSupport {

    public record Market(long issuanceId, String tokenSymbol, long issuerId, long splitRatio) {
    }

    private final AuthService authService;
    private final RiskProfileService riskProfileService;
    private final CashService cashService;
    private final AssetService assetService;
    private final IssuanceService issuanceService;
    private final LedgerPort ledger;

    public TradingTestSupport(AuthService authService, RiskProfileService riskProfileService,
                              CashService cashService, AssetService assetService,
                              IssuanceService issuanceService, LedgerPort ledger) {
        this.authService = authService;
        this.riskProfileService = riskProfileService;
        this.cashService = cashService;
        this.assetService = assetService;
        this.issuanceService = issuanceService;
        this.ledger = ledger;
    }

    /** 성향 5등급(모든 상품 매수 가능) + 예치금 보유 투자자. */
    public long investor(long cash) {
        String email = "trade-" + UUID.randomUUID().toString().substring(0, 12) + "@test.io";
        long id = authService.signup("유통테스터", email, "password-123!").value();
        riskProfileService.submit(InvestorId.of(id), Collections.nCopies(8, 5));
        if (cash > 0) {
            cashService.deposit(InvestorId.of(id), Money.of(cash));
        }
        return id;
    }

    /** LISTED 종목을 만든다. brokerTicker 가 null 이면 괴리율 계산이 생략된다. */
    public Market listedMarket(String brokerTicker, long splitRatio) {
        long issuer = investor(0);
        UnderlyingAsset asset = assetService.create(issuer, "유통 자산",
                UnderlyingAsset.AssetType.REIT, null, brokerTicker, splitRatio, null);
        var created = issuanceService.create(asset.id(), 1_000, 1_000,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(3_600),
                Issuance.AllotmentMethod.FCFS, 3);

        issuanceService.submit(created.issuanceId());
        issuanceService.approve(created.issuanceId());
        openSubscription(created.issuanceId());
        issuanceService.startAllotment(created.issuanceId());
        issuanceService.listIssuance(created.issuanceId());   // 발행인에게 전량 발행 + LISTED

        return new Market(created.issuanceId(), created.tokenSymbol(), issuer, splitRatio);
    }

    private void openSubscription(long issuanceId) {
        try {
            issuanceService.openSubscription(issuanceId);
        } catch (RuntimeException e) {
            if (issuanceService.get(issuanceId).status() != IssuanceStatus.SUBSCRIBING) {
                throw e;
            }
        }
    }

    /** 발행인이 가진 물량을 투자자에게 넘겨 매도 가능 상태로 만든다. */
    public void giveUnits(Market market, long investorId, long units) {
        ledger.transfer(market.tokenSymbol(), OwnerId.of(market.issuerId()), OwnerId.of(investorId),
                Units.of(units), TxRef.of(RefType.ADMIN, "test-seed"));
    }

    public String newKey() {
        return UUID.randomUUID().toString();
    }
}

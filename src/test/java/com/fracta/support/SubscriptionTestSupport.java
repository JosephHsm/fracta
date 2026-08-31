package com.fracta.support;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fracta.account.api.InvestorId;
import com.fracta.account.application.AuthService;
import com.fracta.account.application.CashService;
import com.fracta.account.application.RiskProfileService;
import com.fracta.common.money.Money;
import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.InvalidStateTransitionException;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.issuance.domain.UnderlyingAsset;

/** 청약 테스트 픽스처 — 투자자(등급·예치금)와 SUBSCRIBING 발행 건을 만든다. */
@Component
public class SubscriptionTestSupport {

    public record Ctx(long issuerId, long issuanceId, String tokenSymbol, long unitPrice) {
    }

    private final AuthService authService;
    private final RiskProfileService riskProfileService;
    private final CashService cashService;
    private final AssetService assetService;
    private final IssuanceService issuanceService;

    public SubscriptionTestSupport(AuthService authService, RiskProfileService riskProfileService,
                                   CashService cashService, AssetService assetService,
                                   IssuanceService issuanceService) {
        this.authService = authService;
        this.riskProfileService = riskProfileService;
        this.cashService = cashService;
        this.assetService = assetService;
        this.issuanceService = issuanceService;
    }

    /** gradeLevel 0이면 성향 진단 없음. */
    public long investor(int gradeLevel, long cash) {
        String email = "sub-" + UUID.randomUUID().toString().substring(0, 12) + "@test.io";
        long id = authService.signup("청약테스터", email, "password-123!").value();
        if (gradeLevel > 0) {
            riskProfileService.submit(InvestorId.of(id), Collections.nCopies(8, gradeLevel));
        }
        if (cash > 0) {
            cashService.deposit(InvestorId.of(id), Money.of(cash));
        }
        return id;
    }

    public List<Long> investors(int count, int gradeLevel, long cash) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> investor(gradeLevel, cash))
                .toList();
    }

    public Ctx subscribingIssuance(Issuance.AllotmentMethod method, long totalUnits,
                                   long unitPrice, int riskGrade) {
        Ctx ctx = draftIssuance(method, totalUnits, unitPrice, riskGrade);
        issuanceService.submit(ctx.issuanceId());
        issuanceService.approve(ctx.issuanceId());
        try {
            issuanceService.openSubscription(ctx.issuanceId());
        } catch (InvalidStateTransitionException e) {
            // IS-06 스케줄러가 먼저 전환했을 수 있다
            if (issuanceService.get(ctx.issuanceId()).status() != IssuanceStatus.SUBSCRIBING) {
                throw e;
            }
        }
        return ctx;
    }

    public Ctx draftIssuance(Issuance.AllotmentMethod method, long totalUnits,
                             long unitPrice, int riskGrade) {
        long issuer = investor(0, 0);
        UnderlyingAsset asset = assetService.create(issuer, "청약 자산",
                UnderlyingAsset.AssetType.REIT, null, null, 100, null);
        var created = issuanceService.create(asset.id(), totalUnits, unitPrice,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(3_600), method, riskGrade);
        return new Ctx(issuer, created.issuanceId(), created.tokenSymbol(), unitPrice);
    }
}

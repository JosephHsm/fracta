package com.fracta.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fracta.account.api.AccountQueryPort;
import com.fracta.account.api.InsufficientCashException;
import com.fracta.account.api.InvestorId;
import com.fracta.account.api.RiskProfileRequiredException;
import com.fracta.account.api.SuitabilityMismatchException;
import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.issuance.api.NotSubscribingException;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.subscription.domain.CancellationNotAllowedException;
import com.fracta.subscription.domain.ForbiddenOrderAccessException;
import com.fracta.subscription.domain.IdempotencyConflictException;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.SubscriptionTestSupport;

class SubscriptionFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    SubscriptionService subscriptions;

    @Autowired
    SubscriptionTestSupport support;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    AccountQueryPort accounts;

    private String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("신청: 증거금 = 수량×단가 차감, 잔여 수량 감소, DEPOSITED")
    void applyHoldsMarginAndReserves() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 200, 3);
        long investor = support.investor(3, 10_000);

        var result = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 5, key());

        assertThat(result.status()).isEqualTo("DEPOSITED");
        assertThat(result.depositAmount()).isEqualTo(1_000);      // 5 × 200
        assertThat(accounts.cashBalanceOf(InvestorId.of(investor)).amount()).isEqualTo(9_000);
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(95);
    }

    @Test
    @DisplayName("remaining_units 초과 신청 → FUND_INSUFFICIENT_UNITS")
    void overRemainingRejected() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = support.investor(3, 1_000_000);

        assertThatThrownBy(() -> subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 101, key()))
                .isInstanceOf(InsufficientUnitsException.class);
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(100);
    }

    @Test
    @DisplayName("증거금 부족 → FUND_INSUFFICIENT_CASH, 청약 레코드 미생성 + 잔여 수량 원복")
    void insufficientCashRollsBackReservation() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = support.investor(3, 150);   // 2×100=200 부족

        assertThatThrownBy(() -> subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 2, key()))
                .isInstanceOf(InsufficientCashException.class);

        assertThat(subscriptions.ordersOf(InvestorId.of(investor))).isEmpty();
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(100);
        assertThat(accounts.cashBalanceOf(InvestorId.of(investor)).amount()).isEqualTo(150);
    }

    @Test
    @DisplayName("적합성: 상품 5등급 vs 성향 3등급 → 차단, 진단 없음 → 차단 (차감 이전 검증)")
    void suitabilityCheckedBeforeMargin() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 5);
        long lowGrade = support.investor(3, 10_000);
        long noProfile = support.investor(0, 10_000);

        assertThatThrownBy(() -> subscriptions.apply(ctx.issuanceId(), InvestorId.of(lowGrade), 1, key()))
                .isInstanceOf(SuitabilityMismatchException.class);
        assertThatThrownBy(() -> subscriptions.apply(ctx.issuanceId(), InvestorId.of(noProfile), 1, key()))
                .isInstanceOf(RiskProfileRequiredException.class);

        assertThat(accounts.cashBalanceOf(InvestorId.of(lowGrade)).amount()).isEqualTo(10_000);
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(100);
    }

    @Test
    @DisplayName("SUBSCRIBING이 아닌 발행 건 신청 → STATE_NOT_SUBSCRIBING")
    void notSubscribingRejected() {
        var ctx = support.draftIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = support.investor(3, 10_000);

        assertThatThrownBy(() -> subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 1, key()))
                .isInstanceOf(NotSubscribingException.class);
    }

    @Test
    @DisplayName("취소: 증거금 전액 환불 + remaining_units 복구 + CANCELLED")
    void cancelRefundsAndRestores() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = support.investor(3, 10_000);
        var applied = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 10, key());
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(90);
        assertThat(accounts.cashBalanceOf(InvestorId.of(investor)).amount()).isEqualTo(9_000);

        var cancelled = subscriptions.cancel(applied.orderId(), InvestorId.of(investor));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(accounts.cashBalanceOf(InvestorId.of(investor)).amount()).isEqualTo(10_000);
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(100);
    }

    @Test
    @DisplayName("청약 기간 외(ALLOTTING 이후) 취소 → STATE_ 에러, 타인 주문 취소 → 거부")
    void cancelOutsideWindowOrByOthersRejected() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = support.investor(3, 10_000);
        long other = support.investor(3, 10_000);
        var applied = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 5, key());

        assertThatThrownBy(() -> subscriptions.cancel(applied.orderId(), InvestorId.of(other)))
                .isInstanceOf(ForbiddenOrderAccessException.class);

        issuanceService.startAllotment(ctx.issuanceId());   // 청약 종료
        assertThatThrownBy(() -> subscriptions.cancel(applied.orderId(), InvestorId.of(investor)))
                .isInstanceOf(CancellationNotAllowedException.class);
    }

    @Test
    @DisplayName("멱등성: 동일 키 재요청은 최초 결과 반환, 다른 투자자의 동일 키 → IDEM_KEY_CONFLICT")
    void idempotentReplay() {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = support.investor(3, 10_000);
        long other = support.investor(3, 10_000);
        String sharedKey = key();

        var first = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 5, sharedKey);
        var replayed = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 5, sharedKey);

        assertThat(replayed.orderId()).isEqualTo(first.orderId());
        assertThat(replayed.replayed()).isTrue();
        // 재요청은 추가 차감·추가 예약이 없다
        assertThat(accounts.cashBalanceOf(InvestorId.of(investor)).amount()).isEqualTo(9_500);
        assertThat(issuanceService.get(ctx.issuanceId()).remainingUnits()).isEqualTo(95);

        assertThatThrownBy(() -> subscriptions.apply(ctx.issuanceId(), InvestorId.of(other), 5, sharedKey))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}

package com.fracta.account.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.InvestorId;
import com.fracta.account.api.RiskGrade;
import com.fracta.account.api.RiskProfileRequiredException;
import com.fracta.account.api.SuitabilityMismatchException;
import com.fracta.account.api.SuitabilityPort;
import com.fracta.account.api.SuitabilityResult;
import com.fracta.account.api.SuitabilityScope;
import com.fracta.account.api.SuitabilityResult.Decision;
import com.fracta.account.domain.Investor;
import com.fracta.account.domain.SuitabilityAck;
import com.fracta.account.infrastructure.InvestorRepository;
import com.fracta.account.infrastructure.SuitabilityAckRepository;
import com.fracta.audit.api.Auditable;

/** 적합성 판정·확인 서명 (AC-04). */
@Service
public class SuitabilityService implements SuitabilityPort {

    private final InvestorRepository investors;
    private final SuitabilityAckRepository acks;

    public SuitabilityService(InvestorRepository investors, SuitabilityAckRepository acks) {
        this.investors = investors;
        this.acks = acks;
    }

    @Override
    @Transactional(readOnly = true)
    public SuitabilityResult check(InvestorId investorId, RiskGrade productGrade,
                                   SuitabilityScope scope) {
        Investor investor = investors.findById(investorId.value())
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));

        Instant now = Instant.now();
        RiskGrade investorGrade = investor.validRiskGrade(now);
        if (investorGrade == null) {
            return new SuitabilityResult(Decision.BLOCKED_NO_PROFILE, productGrade, null);
        }
        if (!productGrade.isRiskierThan(investorGrade)) {
            return new SuitabilityResult(Decision.ALLOWED, productGrade, investorGrade);
        }

        // 이 상품에 대한, 아직 유효한, 현재 진단 이후의 서명만 인정한다
        Instant profileIssuedAt = investor.riskGradeIssuedAt();
        boolean acked = profileIssuedAt != null && acks.hasValidAck(
                investorId.value(), scope.type(), scope.id(), productGrade.level(),
                now, profileIssuedAt);
        return new SuitabilityResult(
                acked ? Decision.ALLOWED_BY_ACK : Decision.BLOCKED_MISMATCH,
                productGrade, investorGrade);
    }

    /** 차단이면 예외로 변환 — 컨트롤러·청약(Phase 4)에서 사용. */
    @Transactional(readOnly = true)
    public SuitabilityResult checkOrThrow(InvestorId investorId, RiskGrade productGrade,
                                          SuitabilityScope scope) {
        SuitabilityResult result = check(investorId, productGrade, scope);
        switch (result.decision()) {
            case BLOCKED_NO_PROFILE -> throw new RiskProfileRequiredException(investorId);
            case BLOCKED_MISMATCH -> throw new SuitabilityMismatchException(productGrade, result.investorGrade());
            default -> {
            }
        }
        return result;
    }

    /**
     * 부적합 확인 서명. <b>지정한 상품 하나</b>에 대해서만, 현재 성향 진단이 만료되는
     * 시점까지만 유효하다. 감사 로그 필수.
     *
     * <p>성향 진단이 없거나 만료됐으면 서명할 수 없다 — 무엇에 견줘 부적합한지 정할 수 없다.
     */
    @Transactional
    @Auditable(action = "SUITABILITY_ACK", targetType = "INVESTOR", targetId = "#p0.value()")
    public Instant acknowledge(InvestorId investorId, RiskGrade productGrade,
                               SuitabilityScope scope) {
        Investor investor = investors.findById(investorId.value())
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));
        if (investor.validRiskGrade(Instant.now()) == null) {
            throw new RiskProfileRequiredException(investorId);
        }
        Instant expiresAt = investor.riskGradeExpiresAt();
        acks.save(new SuitabilityAck(investorId.value(), productGrade.level(), scope, expiresAt));
        return expiresAt;
    }
}

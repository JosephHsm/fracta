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
    public SuitabilityResult check(InvestorId investorId, RiskGrade productGrade) {
        Investor investor = investors.findById(investorId.value())
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));

        RiskGrade investorGrade = investor.validRiskGrade(Instant.now());
        if (investorGrade == null) {
            return new SuitabilityResult(Decision.BLOCKED_NO_PROFILE, productGrade, null);
        }
        if (!productGrade.isRiskierThan(investorGrade)) {
            return new SuitabilityResult(Decision.ALLOWED, productGrade, investorGrade);
        }
        boolean acked = acks.existsByInvestorIdAndProductGradeGreaterThanEqual(
                investorId.value(), productGrade.level());
        return new SuitabilityResult(
                acked ? Decision.ALLOWED_BY_ACK : Decision.BLOCKED_MISMATCH,
                productGrade, investorGrade);
    }

    /** 차단이면 예외로 변환 — 컨트롤러·청약(Phase 4)에서 사용. */
    @Transactional(readOnly = true)
    public SuitabilityResult checkOrThrow(InvestorId investorId, RiskGrade productGrade) {
        SuitabilityResult result = check(investorId, productGrade);
        switch (result.decision()) {
            case BLOCKED_NO_PROFILE -> throw new RiskProfileRequiredException(investorId);
            case BLOCKED_MISMATCH -> throw new SuitabilityMismatchException(productGrade, result.investorGrade());
            default -> {
            }
        }
        return result;
    }

    /** 부적합 확인 서명 — 서명한 등급 이하 상품을 커버. 감사 로그 필수. */
    @Transactional
    @Auditable(action = "SUITABILITY_ACK", targetType = "INVESTOR", targetId = "#p0.value()")
    public long acknowledge(InvestorId investorId, RiskGrade productGrade) {
        acks.save(new SuitabilityAck(investorId.value(), productGrade.level()));
        return investorId.value();
    }
}

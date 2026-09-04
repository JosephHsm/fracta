package com.fracta.account.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.account.api.InvestorId;
import com.fracta.account.api.RiskGrade;
import com.fracta.account.domain.Investor;
import com.fracta.account.domain.RiskProfileResult;
import com.fracta.account.domain.RiskProfileScoring;
import com.fracta.account.infrastructure.InvestorRepository;
import com.fracta.account.infrastructure.RiskProfileResultRepository;
import com.fracta.audit.api.Auditable;

/** 투자성향 진단 (AC-03). 결과 유효기간 1년. */
@Service
public class RiskProfileService {

    private static final long VALIDITY_DAYS = 365;

    public record ProfileResult(int score, RiskGrade grade, Instant expiresAt) {
    }

    private final InvestorRepository investors;
    private final RiskProfileResultRepository results;
    private final ObjectMapper objectMapper;

    public RiskProfileService(InvestorRepository investors, RiskProfileResultRepository results,
                              ObjectMapper objectMapper) {
        this.investors = investors;
        this.results = results;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Auditable(action = "RISK_PROFILE_SUBMIT", targetType = "INVESTOR", targetId = "#p0.value()")
    public ProfileResult submit(InvestorId investorId, List<Integer> answers) {
        Investor investor = investors.findById(investorId.value())
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));

        int score = RiskProfileScoring.score(answers);
        RiskGrade grade = RiskProfileScoring.gradeOf(score);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(VALIDITY_DAYS, ChronoUnit.DAYS);

        results.save(new RiskProfileResult(investorId.value(), toJson(answers), score, grade.level(), expiresAt));
        // 발급 시각이 갱신되면 이전 부적합 확인 서명은 전부 효력을 잃는다.
        // 재진단으로 등급이 더 보수적으로 바뀌었는데 옛 서명이 살아 있는 게 제일 위험하다.
        investor.applyRiskProfile(grade, issuedAt, expiresAt);
        return new ProfileResult(score, grade, expiresAt);
    }

    private String toJson(List<Integer> answers) {
        try {
            return objectMapper.writeValueAsString(answers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("설문 답변 직렬화 실패", e);
        }
    }
}

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
    public ProfileResult submit(InvestorId investorId, List<Integer> answers) {
        Investor investor = investors.findById(investorId.value())
                .orElseThrow(() -> new IllegalArgumentException("투자자가 없다: " + investorId.value()));

        int score = RiskProfileScoring.score(answers);
        RiskGrade grade = RiskProfileScoring.gradeOf(score);
        Instant expiresAt = Instant.now().plus(VALIDITY_DAYS, ChronoUnit.DAYS);

        results.save(new RiskProfileResult(investorId.value(), toJson(answers), score, grade.level(), expiresAt));
        investor.applyRiskProfile(grade, expiresAt);
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

package com.fracta.account.domain;

import java.util.List;

import com.fracta.account.api.RiskGrade;

/**
 * 성향 진단 채점 — 순수 함수 (AC-03).
 * 배점표는 docs/appendix/risk-profile-questions.md와 반드시 일치해야 한다.
 */
public final class RiskProfileScoring {

    public static final int QUESTION_COUNT = 8;
    public static final int MIN_ANSWER = 1;
    public static final int MAX_ANSWER = 5;

    private RiskProfileScoring() {
    }

    public static int score(List<Integer> answers) {
        if (answers == null || answers.size() != QUESTION_COUNT) {
            throw new IllegalArgumentException("설문은 정확히 %d문항이어야 한다".formatted(QUESTION_COUNT));
        }
        int total = 0;
        for (Integer answer : answers) {
            if (answer == null || answer < MIN_ANSWER || answer > MAX_ANSWER) {
                throw new IllegalArgumentException("답변은 1~5 사이여야 한다: " + answer);
            }
            total += answer;
        }
        return total;
    }

    /** 총점(8~40) → 5등급 매핑. 경계: 8~12 / 13~19 / 20~26 / 27~33 / 34~40 */
    public static RiskGrade gradeOf(int score) {
        if (score < 8 || score > 40) {
            throw new IllegalArgumentException("총점은 8~40 사이여야 한다: " + score);
        }
        if (score <= 12) {
            return RiskGrade.STABLE;
        }
        if (score <= 19) {
            return RiskGrade.STABLE_SEEKING;
        }
        if (score <= 26) {
            return RiskGrade.NEUTRAL;
        }
        if (score <= 33) {
            return RiskGrade.ACTIVE;
        }
        return RiskGrade.AGGRESSIVE;
    }
}

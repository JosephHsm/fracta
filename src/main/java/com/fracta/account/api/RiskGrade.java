package com.fracta.account.api;

/** 5단계 위험 등급 — 투자자 성향과 상품 위험도 공용 (docs/appendix/risk-profile-questions.md). */
public enum RiskGrade {

    STABLE(1, "안정형"),
    STABLE_SEEKING(2, "안정추구형"),
    NEUTRAL(3, "위험중립형"),
    ACTIVE(4, "적극투자형"),
    AGGRESSIVE(5, "공격투자형");

    private final int level;
    private final String koreanName;

    RiskGrade(int level, String koreanName) {
        this.level = level;
        this.koreanName = koreanName;
    }

    public int level() {
        return level;
    }

    public String koreanName() {
        return koreanName;
    }

    public static RiskGrade fromLevel(int level) {
        for (RiskGrade grade : values()) {
            if (grade.level == level) {
                return grade;
            }
        }
        throw new IllegalArgumentException("등급은 1~5 사이여야 한다: " + level);
    }

    public boolean isRiskierThan(RiskGrade other) {
        return this.level > other.level;
    }
}

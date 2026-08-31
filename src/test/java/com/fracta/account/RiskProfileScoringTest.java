package com.fracta.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.account.api.RiskGrade;
import com.fracta.account.domain.RiskProfileScoring;

/** 8문항 → 5등급 매핑 경계값 (docs/appendix/risk-profile-questions.md와 일치해야 한다). */
class RiskProfileScoringTest {

    @Test
    @DisplayName("등급 경계값: 8~12/13~19/20~26/27~33/34~40")
    void gradeBoundaries() {
        assertThat(RiskProfileScoring.gradeOf(8)).isEqualTo(RiskGrade.STABLE);
        assertThat(RiskProfileScoring.gradeOf(12)).isEqualTo(RiskGrade.STABLE);
        assertThat(RiskProfileScoring.gradeOf(13)).isEqualTo(RiskGrade.STABLE_SEEKING);
        assertThat(RiskProfileScoring.gradeOf(19)).isEqualTo(RiskGrade.STABLE_SEEKING);
        assertThat(RiskProfileScoring.gradeOf(20)).isEqualTo(RiskGrade.NEUTRAL);
        assertThat(RiskProfileScoring.gradeOf(26)).isEqualTo(RiskGrade.NEUTRAL);
        assertThat(RiskProfileScoring.gradeOf(27)).isEqualTo(RiskGrade.ACTIVE);
        assertThat(RiskProfileScoring.gradeOf(33)).isEqualTo(RiskGrade.ACTIVE);
        assertThat(RiskProfileScoring.gradeOf(34)).isEqualTo(RiskGrade.AGGRESSIVE);
        assertThat(RiskProfileScoring.gradeOf(40)).isEqualTo(RiskGrade.AGGRESSIVE);
    }

    @Test
    @DisplayName("총점 범위 밖은 거부")
    void scoreOutOfRange() {
        assertThatThrownBy(() -> RiskProfileScoring.gradeOf(7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskProfileScoring.gradeOf(41)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("채점: 8문항 합산, 문항 수·답변 범위 검증")
    void scoring() {
        assertThat(RiskProfileScoring.score(List.of(1, 1, 1, 1, 1, 1, 1, 1))).isEqualTo(8);
        assertThat(RiskProfileScoring.score(List.of(5, 5, 5, 5, 5, 5, 5, 5))).isEqualTo(40);
        assertThat(RiskProfileScoring.score(List.of(2, 2, 2, 2, 2, 2, 2, 2))).isEqualTo(16);

        assertThatThrownBy(() -> RiskProfileScoring.score(List.of(1, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskProfileScoring.score(Collections.nCopies(8, 6)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskProfileScoring.score(Collections.nCopies(8, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RiskGrade 레벨 매핑과 위험 비교")
    void riskGradeLevels() {
        assertThat(RiskGrade.fromLevel(1)).isEqualTo(RiskGrade.STABLE);
        assertThat(RiskGrade.fromLevel(5)).isEqualTo(RiskGrade.AGGRESSIVE);
        assertThat(RiskGrade.ACTIVE.isRiskierThan(RiskGrade.STABLE_SEEKING)).isTrue();
        assertThat(RiskGrade.STABLE.isRiskierThan(RiskGrade.STABLE)).isFalse();
        assertThatThrownBy(() -> RiskGrade.fromLevel(6)).isInstanceOf(IllegalArgumentException.class);
    }
}

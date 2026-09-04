package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.batch.application.ReconciliationCheck;
import com.fracta.batch.application.ReconciliationCheck.Severity;

/**
 * 불변식 위반의 폭발 반경 분류 (FSD §8.1).
 *
 * <p>전역 집계(INV-3 현금, INV-6)는 여러 테이블 합계를 비교하는 값이라 항이 하나 빠지거나
 * 스냅샷이 어긋나기만 해도 틀린다. 예전에는 그것도 전 종목을 정지시켰다 — 정상 상태에서
 * 플랫폼을 멈추는 쪽이 원래 막으려던 훼손보다 큰 사고다.
 */
class InvariantSeverityTest {

    private static ReconciliationCheck violation(String code, String symbol) {
        return ReconciliationCheck.amounts(code, symbol, BigInteger.ONE, BigInteger.TEN, "테스트");
    }

    @Test
    @DisplayName("토큰 단위 위반은 그 토큰만 멈춘다")
    void tokenScopedViolationsHaltOnlyThatToken() {
        assertThat(violation("INV-1", "FR-T-001").severity()).isEqualTo(Severity.HALT_TOKEN);
        assertThat(violation("INV-2", "FR-T-001").severity()).isEqualTo(Severity.HALT_TOKEN);
        assertThat(violation("INV-3", "FR-T-001").severity()).isEqualTo(Severity.HALT_TOKEN);
        assertThat(violation("INV-5", "FR-T-001").severity()).isEqualTo(Severity.HALT_TOKEN);
    }

    @Test
    @DisplayName("전역 집계 위반은 자동 중단하지 않는다 — 경보만")
    void globalAggregateViolationsOnlyAlert() {
        assertThat(violation("INV-3", ReconciliationCheck.GLOBAL_SCOPE).severity())
                .as("현금 잔고 음수는 전역 집계다")
                .isEqualTo(Severity.ALERT);
        assertThat(violation("INV-6", ReconciliationCheck.GLOBAL_SCOPE).severity())
                .as("예치금 보존식 불일치로 전 종목을 멈추면 안 된다")
                .isEqualTo(Severity.ALERT);
    }

    @Test
    @DisplayName("체인 무결성(INV-4)만은 전역이어도 전체를 멈춘다")
    void chainIntegrityHaltsEverything() {
        assertThat(violation("INV-4", ReconciliationCheck.GLOBAL_SCOPE).severity())
                .as("원장 자체가 훼손됐다는 뜻이다")
                .isEqualTo(Severity.HALT_ALL);
    }
}

package com.fracta.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.common.money.InsufficientUnitsException;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.support.IntegrationTestBase;

/** 불변식 INV-1 ~ INV-4 검증 (FSD §8.1). */
class LedgerTransactionInvariantTest extends IntegrationTestBase {

    @Autowired
    LedgerPort ledger;

    @Autowired
    JdbcTemplate jdbc;

    private static final TxRef REF = TxRef.of(RefType.ADMIN, "invariant-test");

    private String newSymbol() {
        return "INV-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("INV-1: totalIssued(s) == Σ balance(owner, s).units — transfer를 거쳐도 보존")
    void inv1QuantityConservation() {
        String symbol = newSymbol();
        OwnerId a = OwnerId.of(201);
        OwnerId b = OwnerId.of(202);
        OwnerId c = OwnerId.of(203);

        ledger.issue(symbol, a, Units.of(500), REF);
        ledger.issue(symbol, b, Units.of(300), REF);
        ledger.transfer(symbol, a, c, Units.of(120), REF);
        ledger.transfer(symbol, b, a, Units.of(50), REF);

        long balanceSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(units), 0) FROM ledger_balance WHERE token_symbol = ?",
                Long.class, symbol);
        assertThat(ledger.totalIssued(symbol).value()).isEqualTo(800).isEqualTo(balanceSum);

        var result = ledger.verifyInvariant(symbol);
        assertThat(result.valid()).as(String.valueOf(result.violations())).isTrue();
    }

    @Test
    @DisplayName("INV-2: locked_units > units 시도는 DB CHECK가 거부한다")
    void inv2LockedNotExceedingUnitsEnforcedByDb() {
        String symbol = newSymbol();
        OwnerId owner = OwnerId.of(211);
        ledger.issue(symbol, owner, Units.of(10), REF);

        // 앱 계정은 ledger_balance UPDATE 권한이 있지만 CHECK 제약이 마지막 방어선이다
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_balance SET locked_units = units + 1 WHERE token_symbol = ?", symbol))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ledger_balance_invariant");
    }

    @Test
    @DisplayName("INV-3: 잔고 초과 transfer는 InsufficientUnitsException, 잔고 불변 — 음수는 DB CHECK도 거부")
    void inv3NoNegativeBalances() {
        String symbol = newSymbol();
        OwnerId owner = OwnerId.of(221);
        OwnerId other = OwnerId.of(222);
        ledger.issue(symbol, owner, Units.of(10), REF);

        assertThatThrownBy(() -> ledger.transfer(symbol, owner, other, Units.of(11), REF))
                .isInstanceOf(InsufficientUnitsException.class);
        assertThat(ledger.balanceOf(symbol, owner).units()).isEqualTo(Units.of(10));
        assertThat(ledger.balanceOf(symbol, other).units()).isEqualTo(Units.ZERO);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_balance SET units = -1 WHERE token_symbol = ?", symbol))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ledger_balance_invariant");
    }

    @Test
    @DisplayName("INV-4: 전 구간 체인 무결성 — verifyChain 통과")
    void inv4ChainIntegrity() {
        String symbol = newSymbol();
        OwnerId owner = OwnerId.of(231);
        ledger.issue(symbol, owner, Units.of(7), REF);
        ledger.lock(symbol, owner, Units.of(3), REF);
        ledger.unlock(symbol, owner, Units.of(3), REF);

        long maxSeq = jdbc.queryForObject("SELECT MAX(seq) FROM ledger_transaction", Long.class);
        var result = ledger.verifyChain(1, maxSeq);
        assertThat(result.valid()).as(String.valueOf(result)).isTrue();
        assertThat(result.checkedCount()).isEqualTo(maxSeq);
    }
}

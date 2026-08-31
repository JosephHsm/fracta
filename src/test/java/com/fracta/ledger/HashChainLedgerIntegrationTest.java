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
import com.fracta.ledger.api.Balance;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.ledger.infrastructure.LedgerHasher;
import com.fracta.support.IntegrationTestBase;

class HashChainLedgerIntegrationTest extends IntegrationTestBase {

    @Autowired
    LedgerPort ledger;

    @Autowired
    JdbcTemplate jdbc;

    private static final TxRef ADMIN_REF = TxRef.of(RefType.ADMIN, "test");

    private String newSymbol() {
        return "SYM-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Genesis 트랜잭션의 prev_hash는 '0' 64자")
    void genesisPrevHashIsZeros() {
        String symbol = newSymbol();
        ledger.issue(symbol, OwnerId.of(1), Units.of(10), ADMIN_REF);

        String genesisPrev = jdbc.queryForObject(
                "SELECT prev_hash FROM ledger_transaction WHERE seq = 1", String.class);
        assertThat(genesisPrev).isEqualTo(LedgerHasher.GENESIS_PREV_HASH);
    }

    @Test
    @DisplayName("issue → transfer → 잔고·totalIssued 정합")
    void issueAndTransferFlow() {
        String symbol = newSymbol();
        OwnerId alice = OwnerId.of(11);
        OwnerId bob = OwnerId.of(12);

        ledger.issue(symbol, alice, Units.of(100), ADMIN_REF);
        ledger.transfer(symbol, alice, bob, Units.of(30), ADMIN_REF);

        assertThat(ledger.balanceOf(symbol, alice).units()).isEqualTo(Units.of(70));
        assertThat(ledger.balanceOf(symbol, bob).units()).isEqualTo(Units.of(30));
        assertThat(ledger.totalIssued(symbol)).isEqualTo(Units.of(100));
    }

    @Test
    @DisplayName("lock 후 가용 수량이 정확히 감소하고, 가용 초과 transfer는 실패한다")
    void lockReducesAvailableAndBlocksTransfer() {
        String symbol = newSymbol();
        OwnerId owner = OwnerId.of(21);
        OwnerId buyer = OwnerId.of(22);

        ledger.issue(symbol, owner, Units.of(100), ADMIN_REF);
        ledger.lock(symbol, owner, Units.of(60), ADMIN_REF);

        Balance afterLock = ledger.balanceOf(symbol, owner);
        assertThat(afterLock.units()).isEqualTo(Units.of(100));
        assertThat(afterLock.lockedUnits()).isEqualTo(Units.of(60));
        assertThat(afterLock.available()).isEqualTo(Units.of(40));

        // 가용(40) 초과 transfer → 거부, 잔고 불변
        assertThatThrownBy(() -> ledger.transfer(symbol, owner, buyer, Units.of(41), ADMIN_REF))
                .isInstanceOf(InsufficientUnitsException.class);
        assertThat(ledger.balanceOf(symbol, owner).units()).isEqualTo(Units.of(100));
        assertThat(ledger.balanceOf(symbol, buyer).units()).isEqualTo(Units.ZERO);

        // 가용 이내 transfer는 성공
        ledger.transfer(symbol, owner, buyer, Units.of(40), ADMIN_REF);
        assertThat(ledger.balanceOf(symbol, owner).available()).isEqualTo(Units.ZERO);

        // unlock으로 가용 복구
        ledger.unlock(symbol, owner, Units.of(60), ADMIN_REF);
        assertThat(ledger.balanceOf(symbol, owner).available()).isEqualTo(Units.of(60));
    }

    @Test
    @DisplayName("잠금량보다 큰 unlock은 거부한다")
    void unlockMoreThanLockedIsRejected() {
        String symbol = newSymbol();
        OwnerId owner = OwnerId.of(31);
        ledger.issue(symbol, owner, Units.of(10), ADMIN_REF);
        ledger.lock(symbol, owner, Units.of(5), ADMIN_REF);

        assertThatThrownBy(() -> ledger.unlock(symbol, owner, Units.of(6), ADMIN_REF))
                .isInstanceOf(InsufficientUnitsException.class);
        assertThat(ledger.balanceOf(symbol, owner).lockedUnits()).isEqualTo(Units.of(5));
    }

    @Test
    @DisplayName("전 구간 verifyChain 통과")
    void verifyChainFullRange() {
        String symbol = newSymbol();
        OwnerId owner = OwnerId.of(41);
        ledger.issue(symbol, owner, Units.of(5), ADMIN_REF);
        ledger.lock(symbol, owner, Units.of(2), ADMIN_REF);
        ledger.unlock(symbol, owner, Units.of(2), ADMIN_REF);

        long maxSeq = maxSeq();
        var result = ledger.verifyChain(1, maxSeq);
        assertThat(result.valid()).as(String.valueOf(result)).isTrue();
        assertThat(result.checkedCount()).isEqualTo(maxSeq);
    }

    @Test
    @DisplayName("애플리케이션 DB 유저의 ledger_transaction UPDATE/DELETE는 권한 오류 (append-only)")
    void ledgerTransactionIsAppendOnly() {
        String symbol = newSymbol();
        ledger.issue(symbol, OwnerId.of(51), Units.of(1), ADMIN_REF);

        assertThatThrownBy(() -> jdbc.execute("UPDATE ledger_transaction SET units = 999 WHERE seq = 1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");

        assertThatThrownBy(() -> jdbc.execute("DELETE FROM ledger_transaction WHERE seq = 1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    private long maxSeq() {
        Long max = jdbc.queryForObject("SELECT MAX(seq) FROM ledger_transaction", Long.class);
        return max == null ? 0 : max;
    }
}

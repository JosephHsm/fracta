package com.fracta.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.support.IntegrationTestBase;

/**
 * 변조 검출 — 앱 계정은 UPDATE 권한이 없으므로 슈퍼유저(adminJdbc)로 변조를 시뮬레이션한다.
 * 각 테스트는 체인을 원상복구해 다른 테스트의 verifyChain에 영향을 주지 않는다.
 */
class LedgerTamperDetectionTest extends IntegrationTestBase {

    @Autowired
    LedgerPort ledger;

    @Autowired
    JdbcTemplate jdbc;

    private static final TxRef REF = TxRef.of(RefType.ADMIN, "tamper-test");

    private long seedAndPickVictim() {
        String symbol = "TMP-" + UUID.randomUUID().toString().substring(0, 8);
        long victim = 0;
        for (int i = 1; i <= 3; i++) {
            long seq = ledger.issue(symbol, OwnerId.of(100 + i), Units.of(10), REF).seq();
            if (i == 2) {
                victim = seq;
            }
        }
        return victim;
    }

    private long maxSeq() {
        return jdbc.queryForObject("SELECT MAX(seq) FROM ledger_transaction", Long.class);
    }

    @Test
    @DisplayName("units 변조(관리자 권한 UPDATE) 시 verifyChain이 해당 seq를 정확히 지목한다")
    void tamperedUnitsAreDetectedAtExactSeq() {
        long victim = seedAndPickVictim();
        long original = jdbc.queryForObject(
                "SELECT units FROM ledger_transaction WHERE seq = ?", Long.class, victim);
        try {
            adminJdbc().update("UPDATE ledger_transaction SET units = ? WHERE seq = ?",
                    original + 1, victim);

            var result = ledger.verifyChain(1, maxSeq());
            assertThat(result.valid()).isFalse();
            assertThat(result.firstMismatchSeq()).isEqualTo(victim);
            assertThat(result.detail()).contains("curr_hash");
        } finally {
            adminJdbc().update("UPDATE ledger_transaction SET units = ? WHERE seq = ?",
                    original, victim);
        }
        assertThat(ledger.verifyChain(1, maxSeq()).valid()).isTrue();
    }

    @Test
    @DisplayName("prev_hash 변조 시 verifyChain이 해당 seq를 지목한다")
    void tamperedPrevHashIsDetected() {
        long victim = seedAndPickVictim();
        String original = jdbc.queryForObject(
                "SELECT prev_hash FROM ledger_transaction WHERE seq = ?", String.class, victim);
        try {
            adminJdbc().update("UPDATE ledger_transaction SET prev_hash = ? WHERE seq = ?",
                    "f".repeat(64), victim);

            var result = ledger.verifyChain(1, maxSeq());
            assertThat(result.valid()).isFalse();
            assertThat(result.firstMismatchSeq()).isEqualTo(victim);
            assertThat(result.detail()).contains("prev_hash");
        } finally {
            adminJdbc().update("UPDATE ledger_transaction SET prev_hash = ? WHERE seq = ?",
                    original, victim);
        }
        assertThat(ledger.verifyChain(1, maxSeq()).valid()).isTrue();
    }
}

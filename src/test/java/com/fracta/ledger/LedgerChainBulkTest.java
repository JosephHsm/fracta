package com.fracta.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.ledger.infrastructure.HashChainLedgerAdapter;
import com.fracta.support.IntegrationTestBase;

/** FSD §14 명시 조건: 1만 건 INSERT 후 verifyChain(1, 10000) 통과. */
class LedgerChainBulkTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(LedgerChainBulkTest.class);

    private static final long TARGET_SEQ = 10_000;
    private static final int TOP_UP_BATCH = 200;
    private static final int THROUGHPUT_SAMPLE = 300;

    @Autowired
    LedgerPort ledger;

    @Autowired
    HashChainLedgerAdapter adapter;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("1만 건 INSERT 후 verifyChain(1, 10000) 통과 + advisory lock 처리량 실측")
    void tenThousandTransactionsVerify() {
        String symbol = "BULK-01";
        TxRef ref = TxRef.of(RefType.ADMIN, "bulk-test");

        // 체인을 seq 10,000까지 채운다 (배치 트랜잭션으로 top-up — append 경로는 동일)
        TransactionTemplate txt = new TransactionTemplate(txManager);
        long current = maxSeq();
        int ownerRotation = 0;
        while (current < TARGET_SEQ) {
            long n = Math.min(TOP_UP_BATCH, TARGET_SEQ - current);
            final int base = ownerRotation;
            txt.executeWithoutResult(status -> {
                for (int i = 0; i < n; i++) {
                    adapter.issue(symbol, OwnerId.of(9001 + (base + i) % 10), Units.of(1), ref);
                }
            });
            current += n;
            ownerRotation += (int) n;
        }
        assertThat(maxSeq()).isGreaterThanOrEqualTo(TARGET_SEQ);

        long verifyStart = System.nanoTime();
        var result = ledger.verifyChain(1, TARGET_SEQ);
        long verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000;

        assertThat(result.valid()).as(String.valueOf(result)).isTrue();
        assertThat(result.checkedCount()).isEqualTo(TARGET_SEQ);

        // INV-1: 벌크 발행분도 수량 보존
        assertThat(ledger.verifyInvariant(symbol).valid()).isTrue();

        // advisory lock 단건 트랜잭션 처리량 실측 (README 소재)
        long start = System.nanoTime();
        for (int i = 0; i < THROUGHPUT_SAMPLE; i++) {
            ledger.issue(symbol, OwnerId.of(9001 + i % 10), Units.of(1), ref);
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double tps = THROUGHPUT_SAMPLE / seconds;
        log.info("ledger-throughput: single-tx append {}건 / {}s = {} tps, verifyChain(1,{}) = {}ms",
                THROUGHPUT_SAMPLE, String.format("%.2f", seconds), String.format("%.0f", tps),
                TARGET_SEQ, verifyMillis);
        System.out.printf("LEDGER-THROUGHPUT tps=%.0f verify10kMillis=%d%n", tps, verifyMillis);
    }

    private long maxSeq() {
        Long max = jdbc.queryForObject("SELECT MAX(seq) FROM ledger_transaction", Long.class);
        return max == null ? 0 : max;
    }
}

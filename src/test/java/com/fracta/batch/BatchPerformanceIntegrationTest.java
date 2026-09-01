package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.batch.application.BatchJobNames;
import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.ledger.api.TxType;
import com.fracta.ledger.infrastructure.LedgerHasher;
import com.fracta.support.IntegrationTestBase;

/** FSD §13.1: 실제 PostgreSQL에서 10만 잔고 대사와 10만 원장 스트리밍 검증을 실측한다. */
class BatchPerformanceIntegrationTest extends IntegrationTestBase {

    private static final long TARGET_COUNT = 100_000;
    private static final int INSERT_BATCH_SIZE = 2_000;
    private static final String CHAIN_SYMBOL = "PERF-CHAIN-100K";
    private static final String BALANCE_SYMBOL = "PERF-BALANCE-100K";
    private static final long CHAIN_OWNER = 9_900_001L;
    private static final long BALANCE_OWNER = 9_800_001L;

    @Autowired JobLauncher launcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerPort ledger;

    @Autowired
    @Qualifier(BatchJobNames.DAILY_RECONCILIATION)
    Job reconciliationJob;

    @Autowired
    @Qualifier(BatchJobNames.CHAIN_VERIFICATION)
    Job chainJob;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    @DisplayName("10만 원장 스트리밍 검증 + 10만 잔고 대사 5분 이내")
    void verifiesOneHundredThousandRowsWithinTarget() throws Exception {
        seedValidChainToTarget();

        long chainStarted = System.nanoTime();
        JobExecution chainExecution = launcher.run(chainJob, uniqueParameters());
        long chainMillis = Duration.ofNanos(System.nanoTime() - chainStarted).toMillis();

        assertThat(chainExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(checkedCount(chainExecution, "INV-4", "*"))
                .isGreaterThanOrEqualTo(TARGET_COUNT);

        ledger.issue(BALANCE_SYMBOL, OwnerId.of(BALANCE_OWNER), Units.of(TARGET_COUNT),
                TxRef.of(RefType.ADMIN, "batch-performance-balance"));
        replaceWithOneHundredThousandBalances();

        long reconciliationStarted = System.nanoTime();
        JobExecution reconciliationExecution;
        try {
            reconciliationExecution = launcher.run(reconciliationJob, uniqueParameters());
        } finally {
            collapsePerformanceBalances();
        }
        long reconciliationMillis = Duration.ofNanos(
                System.nanoTime() - reconciliationStarted).toMillis();

        assertThat(reconciliationExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(valid(reconciliationExecution, "INV-1", BALANCE_SYMBOL)).isTrue();
        assertThat(reconciliationMillis)
                .as("10만 잔고 대사 실행 시간(ms), 체인 검증 단계 포함")
                .isLessThan(Duration.ofMinutes(5).toMillis());

        System.out.printf("BATCH-PERFORMANCE chain100kMillis=%d balance100kMillis=%d%n",
                chainMillis, reconciliationMillis);
    }

    /** 성능 픽스처만 DBA 경로로 빠르게 구성한다. 운영 애플리케이션에서는 사용할 수 없는 경로다. */
    private void seedValidChainToTarget() {
        JdbcTemplate admin = adminJdbc();
        Long currentValue = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM ledger_transaction", Long.class);
        long current = currentValue == null ? 0 : currentValue;
        if (current >= TARGET_COUNT) {
            return;
        }

        String prevHash = current == 0
                ? LedgerHasher.GENESIS_PREV_HASH
                : jdbc.queryForObject(
                        "SELECT curr_hash FROM ledger_transaction WHERE seq = ?",
                        String.class, current);

        while (current < TARGET_COUNT) {
            int size = (int) Math.min(INSERT_BATCH_SIZE, TARGET_COUNT - current);
            List<LedgerSeed> seeds = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                long seq = current + index + 1;
                Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                String currHash = LedgerHasher.hash(seq, TxType.ISSUE, CHAIN_SYMBOL,
                        null, CHAIN_OWNER, 1, RefType.ADMIN, "batch-performance-chain",
                        createdAt, prevHash);
                seeds.add(new LedgerSeed(seq, createdAt, prevHash, currHash));
                prevHash = currHash;
            }

            admin.batchUpdate("""
                    INSERT INTO ledger_transaction
                        (seq, tx_type, token_symbol, from_owner_id, to_owner_id, units,
                         ref_type, ref_id, created_at, prev_hash, curr_hash)
                    VALUES (?, 'ISSUE', ?, NULL, ?, 1, 'ADMIN', ?, ?, ?, ?)
                    """, seeds, INSERT_BATCH_SIZE, this::bindSeed);
            admin.update("""
                    INSERT INTO ledger_balance (owner_id, token_symbol, units, locked_units, version)
                    VALUES (?, ?, ?, 0, 0)
                    ON CONFLICT (owner_id, token_symbol) DO UPDATE
                    SET units = ledger_balance.units + EXCLUDED.units,
                        version = ledger_balance.version + 1
                    """, CHAIN_OWNER, CHAIN_SYMBOL, size);
            current += size;
        }
    }

    private void bindSeed(PreparedStatement statement, LedgerSeed seed) throws java.sql.SQLException {
        statement.setLong(1, seed.seq());
        statement.setString(2, CHAIN_SYMBOL);
        statement.setLong(3, CHAIN_OWNER);
        statement.setString(4, "batch-performance-chain");
        statement.setObject(5, OffsetDateTime.ofInstant(seed.createdAt(), ZoneOffset.UTC));
        statement.setString(6, seed.prevHash());
        statement.setString(7, seed.currHash());
    }

    private void replaceWithOneHundredThousandBalances() {
        jdbc.update("DELETE FROM ledger_balance WHERE token_symbol = ?", BALANCE_SYMBOL);
        jdbc.update("""
                INSERT INTO ledger_balance (owner_id, token_symbol, units, locked_units, version)
                SELECT 10000000 + value, ?, 1, 0, 0
                FROM generate_series(1, ?) AS value
                """, BALANCE_SYMBOL, TARGET_COUNT);
    }

    private void collapsePerformanceBalances() {
        jdbc.update("DELETE FROM ledger_balance WHERE token_symbol = ?", BALANCE_SYMBOL);
        jdbc.update("""
                INSERT INTO ledger_balance (owner_id, token_symbol, units, locked_units, version)
                VALUES (?, ?, ?, 0, 0)
                """, BALANCE_OWNER, BALANCE_SYMBOL, TARGET_COUNT);
    }

    private long checkedCount(JobExecution execution, String invariant, String symbol) {
        Long count = jdbc.queryForObject("""
                SELECT checked_count FROM reconciliation_result
                WHERE job_execution_id = ? AND invariant_code = ? AND token_symbol = ?
                """, Long.class, execution.getId(), invariant, symbol);
        return count == null ? 0 : count;
    }

    private boolean valid(JobExecution execution, String invariant, String symbol) {
        Boolean value = jdbc.queryForObject("""
                SELECT valid FROM reconciliation_result
                WHERE job_execution_id = ? AND invariant_code = ? AND token_symbol = ?
                """, Boolean.class, execution.getId(), invariant, symbol);
        return Boolean.TRUE.equals(value);
    }

    private JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addString("runDate", LocalDate.now().toString(), true)
                .addString("testRun", UUID.randomUUID().toString(), true)
                .toJobParameters();
    }

    private record LedgerSeed(long seq, Instant createdAt, String prevHash, String currHash) {
    }
}

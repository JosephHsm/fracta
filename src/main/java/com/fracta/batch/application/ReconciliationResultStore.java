package com.fracta.batch.application;

import com.fracta.common.invariant.ReconciliationCheck;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 대사 이력 저장. 배치 Step의 트랜잭션에 참여한다. */
@Service
public class ReconciliationResultStore {

    private final JdbcTemplate jdbc;

    public ReconciliationResultStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long jobExecutionId, LocalDate runDate, ReconciliationCheck check) {
        jdbc.update("""
                INSERT INTO reconciliation_result
                    (job_execution_id, run_date, token_symbol, invariant_code, valid,
                     expected_value, actual_value, difference_value,
                     first_mismatch_seq, checked_count, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_execution_id, token_symbol, invariant_code) DO UPDATE SET
                    valid = EXCLUDED.valid,
                    expected_value = EXCLUDED.expected_value,
                    actual_value = EXCLUDED.actual_value,
                    difference_value = EXCLUDED.difference_value,
                    first_mismatch_seq = EXCLUDED.first_mismatch_seq,
                    checked_count = EXCLUDED.checked_count,
                    detail = EXCLUDED.detail,
                    created_at = now()
                """,
                jobExecutionId, runDate, check.tokenSymbol(), check.invariantCode(), check.valid(),
                decimal(check.expected()), decimal(check.actual()), decimal(check.difference()),
                check.firstMismatchSeq(), check.checkedCount(), check.detail());
    }

    private BigDecimal decimal(java.math.BigInteger value) {
        return value == null ? null : new BigDecimal(value);
    }
}

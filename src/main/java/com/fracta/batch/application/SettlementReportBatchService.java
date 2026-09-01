package com.fracta.batch.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.settlement.api.SettlementReportPort;

/** Phase 6 집계 로직을 재사용해 일별 스냅샷을 멱등 저장한다. */
@Service
public class SettlementReportBatchService {

    private final SettlementReportPort reports;
    private final JdbcTemplate jdbc;

    public SettlementReportBatchService(SettlementReportPort reports, JdbcTemplate jdbc) {
        this.reports = reports;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generate(LocalDate reportDate, long jobExecutionId) {
        SettlementReportPort.DailyReport report = reports.dailyReport(reportDate);
        BigInteger totalFee = BigInteger.valueOf(report.buyFee())
                .add(BigInteger.valueOf(report.sellFee()));
        jdbc.update("""
                INSERT INTO settlement_report
                    (report_date, execution_count, execution_amount, buy_fee, sell_fee,
                     total_fee, job_execution_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (report_date) DO UPDATE SET
                    execution_count = EXCLUDED.execution_count,
                    execution_amount = EXCLUDED.execution_amount,
                    buy_fee = EXCLUDED.buy_fee,
                    sell_fee = EXCLUDED.sell_fee,
                    total_fee = EXCLUDED.total_fee,
                    job_execution_id = EXCLUDED.job_execution_id,
                    updated_at = now()
                """, reportDate, report.executionCount(), decimal(report.executionAmount()),
                decimal(report.buyFee()), decimal(report.sellFee()), new BigDecimal(totalFee),
                jobExecutionId);
    }

    private BigDecimal decimal(long value) {
        return BigDecimal.valueOf(value);
    }
}

package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.InvestorId;
import com.fracta.batch.application.BatchJobNames;
import com.fracta.common.money.Units;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.ledger.api.RefType;
import com.fracta.ledger.api.TxRef;
import com.fracta.subscription.application.SubscriptionAllotmentService;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.SubscriptionTestSupport;
import com.fracta.support.TradingTestSupport;

import io.micrometer.core.instrument.MeterRegistry;

/** DBA 직접 훼손을 실제 PostgreSQL에서 만들고 Phase 9 Job으로 INV-1~6을 각각 검출한다. */
class BatchInvariantIntegrationTest extends IntegrationTestBase {

    @Autowired JobLauncher launcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TradingTestSupport tradingSupport;
    @Autowired SubscriptionTestSupport subscriptionSupport;
    @Autowired SubscriptionService subscriptions;
    @Autowired SubscriptionAllotmentService allotment;
    @Autowired IssuanceService issuanceService;
    @Autowired ListedTokenPort listedTokens;
    @Autowired LedgerPort ledger;
    @Autowired MeterRegistry meters;

    @Autowired
    @Qualifier(BatchJobNames.DAILY_RECONCILIATION)
    Job reconciliationJob;

    @Autowired
    @Qualifier(BatchJobNames.CHAIN_VERIFICATION)
    Job chainJob;

    @Autowired
    Map<String, Job> jobs;

    @Test
    @DisplayName("ledger_balance.units 직접 훼손 → INV-1 기록·종목 중단·자동 복구 없음")
    void detectsInv1AndSuspendsWithoutRepair() throws Exception {
        var market = tradingSupport.listedMarket(null, 100);
        long owner = market.issuerId();
        long original = jdbc.queryForObject("""
                SELECT units FROM ledger_balance WHERE owner_id = ? AND token_symbol = ?
                """, Long.class, owner, market.tokenSymbol());
        double metricBefore = violationMetric();

        try {
            adminJdbc().update("""
                    UPDATE ledger_balance SET units = units + 1
                    WHERE owner_id = ? AND token_symbol = ?
                    """, owner, market.tokenSymbol());

            JobExecution execution = run(reconciliationJob, uniqueParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertViolation(execution, "INV-1", market.tokenSymbol());
            assertThat(listedTokens.findByTokenSymbol(market.tokenSymbol()).orElseThrow().status())
                    .isEqualTo("SUSPENDED");
            assertThat(jdbc.queryForObject("""
                    SELECT units FROM ledger_balance WHERE owner_id = ? AND token_symbol = ?
                    """, Long.class, owner, market.tokenSymbol()))
                    .as("배치는 원인을 숨기는 자동 복구를 하지 않는다")
                    .isEqualTo(original + 1);
            assertThat(violationMetric()).isGreaterThan(metricBefore);
            assertBatchAudit(execution);
        } finally {
            adminJdbc().update("""
                    UPDATE ledger_balance SET units = ?
                    WHERE owner_id = ? AND token_symbol = ?
                    """, original, owner, market.tokenSymbol());
        }
    }

    @Test
    @DisplayName("locked_units > units 직접 훼손 → INV-2 개별 검출")
    void detectsInv2() throws Exception {
        var market = tradingSupport.listedMarket(null, 100);
        long owner = market.issuerId();
        var admin = adminJdbc();
        admin.execute("ALTER TABLE ledger_balance DROP CONSTRAINT ck_ledger_balance_invariant");
        try {
            admin.update("""
                    UPDATE ledger_balance SET locked_units = units + 1
                    WHERE owner_id = ? AND token_symbol = ?
                    """, owner, market.tokenSymbol());
            JobExecution execution = run(reconciliationJob, uniqueParameters());
            assertViolation(execution, "INV-2", market.tokenSymbol());
        } finally {
            admin.update("""
                    UPDATE ledger_balance SET locked_units = 0
                    WHERE owner_id = ? AND token_symbol = ?
                    """, owner, market.tokenSymbol());
            admin.execute("""
                    ALTER TABLE ledger_balance ADD CONSTRAINT ck_ledger_balance_invariant
                    CHECK (units >= 0 AND locked_units >= 0 AND locked_units <= units)
                    """);
        }
    }

    @Test
    @DisplayName("음수 토큰·현금 잔고 직접 훼손 → INV-3 개별 검출")
    void detectsInv3() throws Exception {
        var market = tradingSupport.listedMarket(null, 100);
        long receiver = tradingSupport.investor(0);
        tradingSupport.giveUnits(market, receiver, 10);
        long issuerUnits = ledger.balanceOf(market.tokenSymbol(), OwnerId.of(market.issuerId())).units().value();
        long receiverUnits = ledger.balanceOf(market.tokenSymbol(), OwnerId.of(receiver)).units().value();
        var admin = adminJdbc();
        admin.execute("ALTER TABLE ledger_balance DROP CONSTRAINT ck_ledger_balance_invariant");
        try {
            // 합계는 유지해 INV-1은 정상인 채 INV-3만 깨뜨린다.
            admin.update("""
                    UPDATE ledger_balance SET units = ?
                    WHERE owner_id = ? AND token_symbol = ?
                    """, issuerUnits + receiverUnits + 1, market.issuerId(), market.tokenSymbol());
            admin.update("""
                    UPDATE ledger_balance SET units = -1, locked_units = 0
                    WHERE owner_id = ? AND token_symbol = ?
                    """, receiver, market.tokenSymbol());

            JobExecution execution = run(reconciliationJob, uniqueParameters());
            assertViolation(execution, "INV-3", market.tokenSymbol());
            assertThat(valid(execution, "INV-1", market.tokenSymbol())).isTrue();
        } finally {
            admin.update("""
                    UPDATE ledger_balance SET units = ?
                    WHERE owner_id = ? AND token_symbol = ?
                    """, issuerUnits, market.issuerId(), market.tokenSymbol());
            admin.update("""
                    UPDATE ledger_balance SET units = ?, locked_units = 0
                    WHERE owner_id = ? AND token_symbol = ?
                    """, receiverUnits, receiver, market.tokenSymbol());
            admin.execute("""
                    ALTER TABLE ledger_balance ADD CONSTRAINT ck_ledger_balance_invariant
                    CHECK (units >= 0 AND locked_units >= 0 AND locked_units <= units)
                    """);
        }
    }

    @Test
    @DisplayName("ledger_transaction.units 직접 훼손 → INV-4 최초 불일치 seq + 전체 거래 중단")
    void detectsChainTamperingAtExactSequence() throws Exception {
        var first = tradingSupport.listedMarket(null, 100);
        var second = tradingSupport.listedMarket(null, 100);
        long victim = jdbc.queryForObject("""
                SELECT MIN(seq) FROM ledger_transaction WHERE token_symbol = ?
                """, Long.class, first.tokenSymbol());
        long original = jdbc.queryForObject(
                "SELECT units FROM ledger_transaction WHERE seq = ?", Long.class, victim);
        try {
            adminJdbc().update("UPDATE ledger_transaction SET units = ? WHERE seq = ?",
                    original + 1, victim);
            JobExecution execution = run(chainJob, uniqueParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertBatchAudit(execution);
            assertViolation(execution, "INV-4", "*");
            Long mismatch = jdbc.queryForObject("""
                    SELECT first_mismatch_seq FROM reconciliation_result
                    WHERE job_execution_id = ? AND invariant_code = 'INV-4'
                    """, Long.class, execution.getId());
            assertThat(mismatch).isEqualTo(victim);
            assertThat(listedTokens.findByTokenSymbol(first.tokenSymbol()).orElseThrow().status())
                    .isEqualTo("SUSPENDED");
            assertThat(listedTokens.findByTokenSymbol(second.tokenSymbol()).orElseThrow().status())
                    .isEqualTo("SUSPENDED");
        } finally {
            adminJdbc().update("UPDATE ledger_transaction SET units = ? WHERE seq = ?",
                    original, victim);
        }
    }

    @Test
    @DisplayName("배정 수량 직접 훼손 → INV-5 개별 검출")
    void detectsInv5() throws Exception {
        var ctx = subscriptionSupport.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long investor = subscriptionSupport.investor(3, 10_000);
        var applied = subscriptions.apply(ctx.issuanceId(), InvestorId.of(investor), 10,
                UUID.randomUUID().toString());
        issuanceService.startAllotment(ctx.issuanceId());
        allotment.finalizeAllotment(ctx.issuanceId());

        try {
            adminJdbc().update("UPDATE subscription_order SET allotted_units = 9 WHERE id = ?",
                    applied.orderId());
            JobExecution execution = run(reconciliationJob, uniqueParameters());
            assertViolation(execution, "INV-5", ctx.tokenSymbol());
        } finally {
            adminJdbc().update("UPDATE subscription_order SET allotted_units = 10 WHERE id = ?",
                    applied.orderId());
        }
    }

    @Test
    @DisplayName("investor.cash_balance 직접 훼손 → INV-6 검출")
    void detectsInv6() throws Exception {
        long investor = tradingSupport.investor(1_000);
        try {
            adminJdbc().update("UPDATE investor SET cash_balance = cash_balance + 1 WHERE id = ?", investor);
            JobExecution execution = run(reconciliationJob, uniqueParameters());
            assertViolation(execution, "INV-6", "*");
        } finally {
            adminJdbc().update("UPDATE investor SET cash_balance = cash_balance - 1 WHERE id = ?", investor);
        }
    }

    @Test
    @DisplayName("6개 Job 등록 + 동일 식별 파라미터 두 번째 실행 거부")
    void registersAllJobsAndRejectsDuplicateRun() throws Exception {
        assertThat(jobs.keySet()).contains(
                BatchJobNames.DAILY_RECONCILIATION,
                BatchJobNames.CHAIN_VERIFICATION,
                BatchJobNames.SETTLEMENT_REPORT,
                BatchJobNames.BROKER_TOKEN_REFRESH,
                BatchJobNames.SUBSCRIPTION_ALLOTMENT,
                BatchJobNames.API_LOG_ARCHIVE);

        JobParameters parameters = uniqueParameters();
        JobExecution first = run(chainJob, parameters);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThatThrownBy(() -> launcher.run(chainJob, parameters))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    private JobExecution run(Job job, JobParameters parameters) throws Exception {
        return launcher.run(job, parameters);
    }

    private JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addString("runDate", LocalDate.now().toString(), true)
                .addString("testRun", UUID.randomUUID().toString(), true)
                .toJobParameters();
    }

    private void assertViolation(JobExecution execution, String invariant, String symbol) {
        assertThat(valid(execution, invariant, symbol))
                .as("execution=%d %s %s", execution.getId(), invariant, symbol)
                .isFalse();
    }

    private boolean valid(JobExecution execution, String invariant, String symbol) {
        Boolean value = jdbc.queryForObject("""
                SELECT valid FROM reconciliation_result
                WHERE job_execution_id = ? AND invariant_code = ? AND token_symbol = ?
                """, Boolean.class, execution.getId(), invariant, symbol);
        return Boolean.TRUE.equals(value);
    }

    private void assertBatchAudit(JobExecution execution) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'BATCH_JOB_EXECUTE' AND target_id = ? AND channel = 'BATCH'
                """, Long.class, String.valueOf(execution.getId()));
        assertThat(count).isEqualTo(1);
    }

    private double violationMetric() {
        var counter = meters.find("fracta.ledger.invariant.violation")
                .tag("source", "batch").counter();
        return counter == null ? 0 : counter.count();
    }
}

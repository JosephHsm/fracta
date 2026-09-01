package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fracta.account.api.InvestorId;
import com.fracta.batch.application.BatchJobNames;
import com.fracta.batch.application.SettlementReportBatchService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.infrastructure.HashChainLedgerAdapter;
import com.fracta.subscription.application.SubscriptionService;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.SubscriptionTestSupport;

/** 정산·토큰·배정·아카이브 Job의 Phase 1~8 서비스 연결을 검증한다. */
class BatchOperationalIntegrationTest extends IntegrationTestBase {

    @Autowired JobLauncher launcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubscriptionTestSupport support;
    @Autowired SubscriptionService subscriptions;
    @Autowired IssuanceService issuanceService;
    @Autowired LedgerPort ledger;

    @SpyBean HashChainLedgerAdapter ledgerAdapter;
    @SpyBean SettlementReportBatchService settlementReports;

    @Autowired @Qualifier(BatchJobNames.SETTLEMENT_REPORT) Job settlementJob;
    @Autowired @Qualifier(BatchJobNames.BROKER_TOKEN_REFRESH) Job brokerTokenJob;
    @Autowired @Qualifier(BatchJobNames.SUBSCRIPTION_ALLOTMENT) Job subscriptionJob;
    @Autowired @Qualifier(BatchJobNames.API_LOG_ARCHIVE) Job archiveJob;

    @Test
    @DisplayName("SettlementReportJob이 Phase 6 집계를 일별 스냅샷으로 저장한다")
    void settlementReportPersistsSnapshot() throws Exception {
        LocalDate date = LocalDate.now();
        JobExecution execution = launcher.run(settlementJob, dated(date));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertBatchAudit(execution);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_report WHERE report_date = ?",
                Long.class, date);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("BrokerTokenRefreshJob은 자격증명 없는 CI에서 외부 호출 없이 완료한다")
    void brokerRefreshUsesExistingManager() throws Exception {
        JobParameters parameters = new JobParametersBuilder(dated(LocalDate.now()))
                .addString("slot", UUID.randomUUID().toString(), true)
                .toJobParameters();
        JobExecution execution = launcher.run(brokerTokenJob, parameters);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertBatchAudit(execution);
    }

    @Test
    @DisplayName("SettlementReportJob은 일시 실패를 정확히 3회 재시도한다")
    void settlementReportRetriesThreeTimes() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() < 4) {
                throw new RuntimeException("temporary settlement failure");
            }
            return invocation.callRealMethod();
        }).when(settlementReports).generate(any(), anyLong());

        JobExecution execution = launcher.run(settlementJob, dated(LocalDate.now()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(attempts).hasValue(4);
        assertBatchAudit(execution);
    }

    @Test
    @DisplayName("SubscriptionAllotmentJob 중간 실패 → 상태·원장·주문 전체 롤백")
    void allotmentJobRollsBackCompletely() throws Exception {
        var ctx = support.subscribingIssuance(Issuance.AllotmentMethod.FCFS, 100, 100, 3);
        long first = support.investor(3, 5_000);
        long second = support.investor(3, 5_000);
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(first), 10, UUID.randomUUID().toString());
        subscriptions.apply(ctx.issuanceId(), InvestorId.of(second), 20, UUID.randomUUID().toString());

        // 테스트 목적의 운영 시각 진행: 청약 종료 스캐너가 이 발행을 선택할 수 있게 한다.
        adminJdbc().update("UPDATE issuance SET subscription_end_at = ? WHERE id = ?",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1), ctx.issuanceId());

        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new RuntimeException("chaos: 배치 배정 두 번째 발행 실패");
            }
            return invocation.callRealMethod();
        }).when(ledgerAdapter).issue(anyString(), any(), any(), any());

        Instant scheduledAt = Instant.now();
        JobParameters parameters = new JobParametersBuilder(dated(LocalDate.now()))
                .addLong("issuanceId", ctx.issuanceId(), true)
                .addString("scheduledAt", scheduledAt.toString(), false)
                .toJobParameters();
        JobExecution execution = launcher.run(subscriptionJob, parameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertBatchAudit(execution);
        assertThat(issuanceService.get(ctx.issuanceId()).status())
                .as("SUBSCRIBING→ALLOTTING 전이도 같은 Step 트랜잭션에서 롤백")
                .isEqualTo(IssuanceStatus.SUBSCRIBING);
        assertThat(ledger.totalIssued(ctx.tokenSymbol()).value()).isZero();
        Long deposited = jdbc.queryForObject("""
                SELECT COUNT(*) FROM subscription_order
                WHERE issuance_id = ? AND status = 'DEPOSITED'
                """, Long.class, ctx.issuanceId());
        assertThat(deposited).isEqualTo(2);
        assertThatThrownBy(() -> launcher.run(subscriptionJob, parameters))
                .as("실패한 배정을 스케줄러가 자동 재시도하지 않고 수동 개입 대상으로 남긴다")
                .isInstanceOf(JobRestartException.class);
    }

    @Test
    @DisplayName("ApiLogArchiveJob은 90일 경과 로그만 이동하고 최근 로그는 보존한다")
    void archivesOnlyExpiredApiLogs() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long oldId = jdbc.queryForObject("""
                INSERT INTO api_call_log
                    (client_id, endpoint, method, status_code, latency_ms, called_at)
                VALUES (?, '/old', 'GET', 200, 1, now() - interval '91 days')
                RETURNING id
                """, Long.class, "archive-old-" + suffix);
        Long recentId = jdbc.queryForObject("""
                INSERT INTO api_call_log
                    (client_id, endpoint, method, status_code, latency_ms, called_at)
                VALUES (?, '/recent', 'GET', 200, 1, now() - interval '89 days')
                RETURNING id
                """, Long.class, "archive-recent-" + suffix);

        JobExecution execution = launcher.run(archiveJob, dated(LocalDate.now()));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertBatchAudit(execution);
        assertThat(count("SELECT COUNT(*) FROM api_call_log WHERE id = ?", oldId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM api_call_log_archive WHERE original_id = ?", oldId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM api_call_log WHERE id = ?", recentId))
                .isEqualTo(1);
        jdbc.update("DELETE FROM api_call_log WHERE id = ?", recentId);
    }

    private JobParameters dated(LocalDate date) {
        return new JobParametersBuilder()
                .addString("runDate", date.toString(), true)
                .addString("testRun", UUID.randomUUID().toString(), true)
                .toJobParameters();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void assertBatchAudit(JobExecution execution) {
        assertThat(count("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'BATCH_JOB_EXECUTE' AND target_id = ? AND channel = 'BATCH'
                """, String.valueOf(execution.getId()))).isEqualTo(1);
    }
}

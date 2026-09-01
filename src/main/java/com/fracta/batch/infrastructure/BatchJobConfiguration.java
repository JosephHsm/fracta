package com.fracta.batch.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.NeverSkipItemSkipPolicy;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.retry.policy.NeverRetryPolicy;

import com.fracta.batch.application.ApiLogArchiveService;
import com.fracta.batch.application.BatchExecutionListener;
import com.fracta.batch.application.BatchJobNames;
import com.fracta.batch.application.BatchRetryExecutor;
import com.fracta.batch.application.InvariantViolationHandler;
import com.fracta.batch.application.ReconciliationCheck;
import com.fracta.batch.application.ReconciliationResultStore;
import com.fracta.batch.application.SettlementReportBatchService;
import com.fracta.external.broker.api.BrokerTokenRefreshPort;
import com.fracta.issuance.api.IssuanceBatchPort;
import com.fracta.ledger.api.ChainVerifyResult;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.subscription.api.SubscriptionBatchPort;

/** Phase 9의 6개 Job과 Step. JobParameters에 실행 일자를 식별 파라미터로 사용한다. */
@Configuration
public class BatchJobConfiguration {

    static final int RECONCILIATION_CHUNK_SIZE = 100;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    @StepScope
    JdbcCursorItemReader<String> reconciliationTokenReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<String>()
                .name("reconciliationTokenReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT token_symbol FROM (
                            SELECT DISTINCT token_symbol FROM ledger_transaction
                            UNION
                            SELECT token_symbol FROM issuance
                            WHERE status IN ('LISTED', 'SUSPENDED')
                        ) symbols
                        ORDER BY token_symbol
                        """)
                .rowMapper((rs, rowNum) -> rs.getString(1))
                .fetchSize(RECONCILIATION_CHUNK_SIZE)
                .saveState(true)
                .build();
    }

    @Bean
    ItemProcessor<String, List<ReconciliationCheck>> reconciliationProcessor(
            BatchReconciliationQuery query) {
        return query::checkToken;
    }

    @Bean
    @StepScope
    ItemWriter<List<ReconciliationCheck>> reconciliationWriter(
            ReconciliationResultStore store,
            InvariantViolationHandler violationHandler,
            @Value("#{jobParameters['runDate']}") String runDateValue,
            @Value("#{stepExecution.jobExecution.id}") Long jobExecutionId) {
        LocalDate runDate = LocalDate.parse(runDateValue);
        return chunk -> {
            for (List<ReconciliationCheck> checks : chunk.getItems()) {
                for (ReconciliationCheck check : checks) {
                    store.save(jobExecutionId, runDate, check);
                    violationHandler.handle(BatchJobNames.DAILY_RECONCILIATION,
                            jobExecutionId, check);
                }
            }
        };
    }

    @Bean
    Step reconciliationTokenStep(JobRepository jobs, PlatformTransactionManager transactions,
                                 JdbcCursorItemReader<String> reconciliationTokenReader,
                                 ItemProcessor<String, List<ReconciliationCheck>> reconciliationProcessor,
                                 ItemWriter<List<ReconciliationCheck>> reconciliationWriter) {
        return new StepBuilder("reconciliationTokenStep", jobs)
                .<String, List<ReconciliationCheck>>chunk(RECONCILIATION_CHUNK_SIZE, transactions)
                .reader(reconciliationTokenReader)
                .processor(reconciliationProcessor)
                .writer(reconciliationWriter)
                .faultTolerant()
                .skipPolicy(new NeverSkipItemSkipPolicy())
                .retryPolicy(new NeverRetryPolicy())
                .build();
    }

    @Bean
    Step reconciliationGlobalStep(JobRepository jobs, PlatformTransactionManager transactions,
                                  BatchReconciliationQuery query,
                                  ReconciliationResultStore store,
                                  InvariantViolationHandler violationHandler) {
        return new StepBuilder("reconciliationGlobalStep", jobs)
                .tasklet((contribution, context) -> {
                    JobExecution execution = contribution.getStepExecution().getJobExecution();
                    LocalDate runDate = runDate(execution);
                    for (ReconciliationCheck check : query.checkGlobal()) {
                        store.save(execution.getId(), runDate, check);
                        violationHandler.handle(BatchJobNames.DAILY_RECONCILIATION,
                                execution.getId(), check);
                    }
                    return RepeatStatus.FINISHED;
                }, transactions)
                .build();
    }

    @Bean
    Step chainVerificationStep(JobRepository jobs, PlatformTransactionManager transactions,
                               BatchReconciliationQuery query,
                               ReconciliationResultStore store,
                               InvariantViolationHandler violationHandler,
                               LedgerPort ledger) {
        return new StepBuilder("chainVerificationStep", jobs)
                .tasklet((contribution, context) -> {
                    JobExecution execution = contribution.getStepExecution().getJobExecution();
                    long maxSeq = query.maxLedgerSeq();
                    ReconciliationCheck check;
                    if (maxSeq == 0) {
                        check = new ReconciliationCheck("INV-4", ReconciliationCheck.GLOBAL_SCOPE,
                                true, null, null, null, null, 0L, "원장 트랜잭션 0건");
                    } else {
                        ChainVerifyResult result = ledger.verifyChain(1, maxSeq);
                        check = new ReconciliationCheck("INV-4", ReconciliationCheck.GLOBAL_SCOPE,
                                result.valid(), null, null, null, result.firstMismatchSeq(),
                                result.checkedCount(), result.valid()
                                ? "해시체인 %d건 검증 완료".formatted(result.checkedCount())
                                : result.detail());
                    }
                    store.save(execution.getId(), runDate(execution), check);
                    violationHandler.handle(execution.getJobInstance().getJobName(),
                            execution.getId(), check);
                    return RepeatStatus.FINISHED;
                }, transactions)
                .build();
    }

    @Bean(name = BatchJobNames.DAILY_RECONCILIATION)
    Job dailyReconciliationJob(JobRepository jobs,
                               Step reconciliationTokenStep,
                               Step reconciliationGlobalStep,
                               Step chainVerificationStep,
                               BatchExecutionListener listener) {
        return new JobBuilder(BatchJobNames.DAILY_RECONCILIATION, jobs)
                .listener(listener)
                .start(reconciliationTokenStep)
                .next(reconciliationGlobalStep)
                .next(chainVerificationStep)
                .build();
    }

    @Bean(name = BatchJobNames.CHAIN_VERIFICATION)
    Job chainVerificationJob(JobRepository jobs, Step chainVerificationStep,
                             BatchExecutionListener listener) {
        return new JobBuilder(BatchJobNames.CHAIN_VERIFICATION, jobs)
                .listener(listener)
                .start(chainVerificationStep)
                .build();
    }

    @Bean
    Step settlementReportStep(JobRepository jobs, PlatformTransactionManager transactions,
                              SettlementReportBatchService service, BatchRetryExecutor retry) {
        return new StepBuilder("settlementReportStep", jobs)
                .tasklet((contribution, context) -> {
                    JobExecution execution = contribution.getStepExecution().getJobExecution();
                    retry.run(3, () -> service.generate(runDate(execution), execution.getId()));
                    return RepeatStatus.FINISHED;
                }, transactions)
                .build();
    }

    @Bean(name = BatchJobNames.SETTLEMENT_REPORT)
    Job settlementReportJob(JobRepository jobs, Step settlementReportStep,
                            BatchExecutionListener listener) {
        return new JobBuilder(BatchJobNames.SETTLEMENT_REPORT, jobs)
                .listener(listener)
                .start(settlementReportStep)
                .build();
    }

    @Bean
    Step brokerTokenRefreshStep(JobRepository jobs, PlatformTransactionManager transactions,
                                BrokerTokenRefreshPort tokens, BatchRetryExecutor retry) {
        return new StepBuilder("brokerTokenRefreshStep", jobs)
                .tasklet((contribution, context) -> {
                    retry.run(1, tokens::refreshIfDueOrThrow);
                    return RepeatStatus.FINISHED;
                }, transactions)
                .build();
    }

    @Bean(name = BatchJobNames.BROKER_TOKEN_REFRESH)
    Job brokerTokenRefreshJob(JobRepository jobs, Step brokerTokenRefreshStep,
                              BatchExecutionListener listener) {
        return new JobBuilder(BatchJobNames.BROKER_TOKEN_REFRESH, jobs)
                .listener(listener)
                .start(brokerTokenRefreshStep)
                .build();
    }

    @Bean
    Step subscriptionAllotmentStep(JobRepository jobs, PlatformTransactionManager transactions,
                                   IssuanceBatchPort issuances,
                                   SubscriptionBatchPort subscriptions) {
        return new StepBuilder("subscriptionAllotmentStep", jobs)
                .tasklet((contribution, context) -> {
                    JobExecution execution = contribution.getStepExecution().getJobExecution();
                    Long issuanceId = execution.getJobParameters().getLong("issuanceId");
                    String scheduledAt = execution.getJobParameters().getString("scheduledAt");
                    if (issuanceId == null || scheduledAt == null) {
                        throw new IllegalArgumentException(
                                "SubscriptionAllotmentJob은 issuanceId와 scheduledAt이 필수다");
                    }
                    issuances.startAllotment(issuanceId, Instant.parse(scheduledAt));
                    subscriptions.finalizeAllotment(issuanceId);
                    return RepeatStatus.FINISHED;
                }, transactions)
                .build();
    }

    @Bean(name = BatchJobNames.SUBSCRIPTION_ALLOTMENT)
    Job subscriptionAllotmentJob(JobRepository jobs, Step subscriptionAllotmentStep,
                                 BatchExecutionListener listener) {
        return new JobBuilder(BatchJobNames.SUBSCRIPTION_ALLOTMENT, jobs)
                .listener(listener)
                .preventRestart()
                .start(subscriptionAllotmentStep)
                .build();
    }

    @Bean
    Step apiLogArchiveStep(JobRepository jobs, PlatformTransactionManager transactions,
                           ApiLogArchiveService archive, BatchRetryExecutor retry) {
        return new StepBuilder("apiLogArchiveStep", jobs)
                .tasklet((contribution, context) -> {
                    JobExecution execution = contribution.getStepExecution().getJobExecution();
                    Instant cutoff = runDate(execution).atTime(2, 0).atZone(KST)
                            .minusDays(90).toInstant();
                    retry.run(3, () -> archive.archiveBefore(cutoff, execution.getId()));
                    return RepeatStatus.FINISHED;
                }, transactions)
                .build();
    }

    @Bean(name = BatchJobNames.API_LOG_ARCHIVE)
    Job apiLogArchiveJob(JobRepository jobs, Step apiLogArchiveStep,
                         BatchExecutionListener listener) {
        return new JobBuilder(BatchJobNames.API_LOG_ARCHIVE, jobs)
                .listener(listener)
                .start(apiLogArchiveStep)
                .build();
    }

    private static LocalDate runDate(JobExecution execution) {
        String value = execution.getJobParameters().getString("runDate");
        if (value == null) {
            throw new IllegalArgumentException("runDate JobParameter가 필요하다");
        }
        return LocalDate.parse(value);
    }
}

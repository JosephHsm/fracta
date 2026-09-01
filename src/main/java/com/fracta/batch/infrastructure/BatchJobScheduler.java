package com.fracta.batch.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fracta.batch.application.BatchJobNames;
import com.fracta.common.logging.RequestIdFilter;
import com.fracta.issuance.api.IssuanceBatchPort;

/** 6개 Job 스케줄과 Redis 중복 기동 방어. JobRepository가 동일 파라미터 중복도 재차 막는다. */
@Component
@ConditionalOnProperty(name = "batch.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BatchJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchJobScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JobLauncher launcher;
    private final RedissonClient redisson;
    private final IssuanceBatchPort issuances;
    private final Job reconciliationJob;
    private final Job chainJob;
    private final Job settlementJob;
    private final Job brokerTokenJob;
    private final Job subscriptionJob;
    private final Job archiveJob;

    public BatchJobScheduler(
            JobLauncher launcher,
            RedissonClient redisson,
            IssuanceBatchPort issuances,
            @Qualifier(BatchJobNames.DAILY_RECONCILIATION) Job reconciliationJob,
            @Qualifier(BatchJobNames.CHAIN_VERIFICATION) Job chainJob,
            @Qualifier(BatchJobNames.SETTLEMENT_REPORT) Job settlementJob,
            @Qualifier(BatchJobNames.BROKER_TOKEN_REFRESH) Job brokerTokenJob,
            @Qualifier(BatchJobNames.SUBSCRIPTION_ALLOTMENT) Job subscriptionJob,
            @Qualifier(BatchJobNames.API_LOG_ARCHIVE) Job archiveJob) {
        this.launcher = launcher;
        this.redisson = redisson;
        this.issuances = issuances;
        this.reconciliationJob = reconciliationJob;
        this.chainJob = chainJob;
        this.settlementJob = settlementJob;
        this.brokerTokenJob = brokerTokenJob;
        this.subscriptionJob = subscriptionJob;
        this.archiveJob = archiveJob;
    }

    @Scheduled(cron = "${batch.schedule.reconciliation:0 0 23 * * *}", zone = "Asia/Seoul")
    public void reconcileDaily() {
        LocalDate date = LocalDate.now(KST);
        launch(reconciliationJob, dated(date), date.toString());
    }

    @Scheduled(cron = "${batch.schedule.chain-verification:0 30 23 * * *}", zone = "Asia/Seoul")
    public void verifyChainDaily() {
        LocalDate date = LocalDate.now(KST);
        launch(chainJob, dated(date), date.toString());
    }

    @Scheduled(cron = "${batch.schedule.settlement-report:0 0 18 * * *}", zone = "Asia/Seoul")
    public void reportSettlementDaily() {
        LocalDate date = LocalDate.now(KST);
        launch(settlementJob, dated(date), date.toString());
    }

    @Scheduled(cron = "${batch.schedule.broker-token-refresh:0 0/30 * * * *}", zone = "Asia/Seoul")
    public void refreshBrokerToken() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        int minute = now.getMinute() < 30 ? 0 : 30;
        String slot = now.withMinute(minute).withSecond(0).withNano(0).toString();
        JobParameters parameters = new JobParametersBuilder()
                .addString("runDate", now.toLocalDate().toString(), true)
                .addString("slot", slot, true)
                .toJobParameters();
        launch(brokerTokenJob, parameters, slot);
    }

    /** 청약 종료 시각을 분 단위로 스캔한다. Job 안의 트랜잭션이 상태 전이와 배정을 함께 묶는다. */
    @Scheduled(fixedDelayString = "${batch.schedule.allotment-scan-delay:60000}")
    public void allotDueSubscriptions() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDate date = LocalDate.now(KST);
        for (long issuanceId : issuances.findDueForAllotment(now)) {
            JobParameters parameters = new JobParametersBuilder()
                    .addString("runDate", date.toString(), true)
                    .addLong("issuanceId", issuanceId, true)
                    .addString("scheduledAt", now.toString(), false)
                    .toJobParameters();
            launch(subscriptionJob, parameters, date + ":" + issuanceId);
        }
    }

    @Scheduled(cron = "${batch.schedule.api-log-archive:0 0 2 * * SUN}", zone = "Asia/Seoul")
    public void archiveApiLogsWeekly() {
        LocalDate date = LocalDate.now(KST);
        launch(archiveJob, dated(date), date.toString());
    }

    private JobParameters dated(LocalDate date) {
        return new JobParametersBuilder()
                .addString("runDate", date.toString(), true)
                .toJobParameters();
    }

    private void launch(Job job, JobParameters parameters, String executionKey) {
        String requestId = "batch-launch-" + UUID.randomUUID();
        MDC.put(RequestIdFilter.REQUEST_ID_KEY, requestId);
        RLock lock = redisson.getLock("batch:lock:" + job.getName() + ":" + executionKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 2, TimeUnit.HOURS);
            if (!acquired) {
                log.warn("배치 중복 기동 차단: job={} key={}", job.getName(), executionKey);
                return;
            }
            launcher.run(job, parameters);
        } catch (org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException
                 | org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
                 | org.springframework.batch.core.repository.JobRestartException e) {
            log.info("동일 파라미터 중복 또는 수동 개입 대상 배치 실행 거부: job={} key={}",
                    job.getName(), executionKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("배치 락 대기 중 인터럽트: job={} key={}", job.getName(), executionKey);
        } catch (Exception e) {
            log.error("배치 기동 실패: job={} key={}", job.getName(), executionKey, e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            MDC.remove(RequestIdFilter.REQUEST_ID_KEY);
        }
    }
}

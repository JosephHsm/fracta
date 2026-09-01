package com.fracta.batch.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.audit.api.AuditEntry;
import com.fracta.audit.infrastructure.AuditLogWriter;
import com.fracta.batch.api.BatchAlertEvent;
import com.fracta.common.logging.ActorMdcFilter;
import com.fracta.common.logging.RequestIdFilter;

/** 모든 Job의 requestId·실패 경보·BATCH 감사 로그를 한곳에서 보장한다. */
@Component
public class BatchExecutionListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutionListener.class);

    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public BatchExecutionListener(AuditLogWriter auditLogWriter, ObjectMapper objectMapper,
                                  ApplicationEventPublisher events) {
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String requestId = "batch-%s-%d".formatted(
                jobExecution.getJobInstance().getJobName(), jobExecution.getId());
        MDC.put(RequestIdFilter.REQUEST_ID_KEY, requestId);
        MDC.put(RequestIdFilter.CHANNEL_KEY, "BATCH");
        MDC.put(ActorMdcFilter.ACTOR_KEY, "system");
        log.info("배치 시작: job={} executionId={} params={}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        try {
            String state = objectMapper.writeValueAsString(java.util.Map.of(
                    "status", jobExecution.getStatus().name(),
                    "exitCode", jobExecution.getExitStatus().getExitCode(),
                    "startedAt", String.valueOf(jobExecution.getStartTime()),
                    "endedAt", String.valueOf(jobExecution.getEndTime())));
            auditLogWriter.write(new AuditEntry(
                    "system", "BATCH_JOB_EXECUTE", "BATCH_JOB",
                    String.valueOf(jobExecution.getId()), "BATCH", null, state,
                    MDC.get(RequestIdFilter.REQUEST_ID_KEY)));

            if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
                String detail = jobExecution.getAllFailureExceptions().stream()
                        .map(e -> e.getClass().getSimpleName() + ": " + e.getMessage())
                        .limit(5)
                        .collect(java.util.stream.Collectors.joining(" | "));
                log.error("배치 실패: job={} executionId={} detail={}",
                        jobName, jobExecution.getId(), detail);
                events.publishEvent(new BatchAlertEvent(jobName, "ERROR", null, null,
                        detail, jobExecution.getId(), Instant.now()));
            } else {
                log.info("배치 완료: job={} executionId={}", jobName, jobExecution.getId());
            }
        } catch (Exception e) {
            // 리스너 오류로 원래 Job 상태를 덮어쓰지 않는다. 감사 실패 자체는 상세 로그로 남긴다.
            log.error("배치 종료 감사 기록 실패: job={} executionId={}",
                    jobName, jobExecution.getId(), e);
        } finally {
            MDC.remove(RequestIdFilter.REQUEST_ID_KEY);
            MDC.remove(RequestIdFilter.CHANNEL_KEY);
            MDC.remove(ActorMdcFilter.ACTOR_KEY);
        }
    }
}

package com.fracta.batch.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fracta.audit.api.Auditable;

/**
 * 일일 대사를 <b>지금</b> 돌린다 (운영·시연용).
 *
 * <p>왜 필요한가 — 스케줄러의 {@code reconcileDaily()}는 JobParameter가 `runDate` 하나라
 * 하루에 한 번만 실행된다. 운영에서는 그게 맞다(중복 대사는 의미가 없다). 하지만
 * <b>사고가 났을 때 "지금 당장 대사를 돌려라"가 불가능</b>해진다. 원장 훼손이 의심되는데
 * 자정까지 기다릴 수는 없다.
 *
 * <p>여기서는 `requestedAt`을 식별 파라미터에 넣어 매번 새 JobInstance로 만든다.
 * 스케줄러가 꺼져 있어도(`batch.scheduling.enabled=false`) 동작한다 — 그 플래그는
 * 자동 실행만 끄는 것이지 수동 개입까지 막을 이유가 없다.
 *
 * <p>동기 실행이다. 호출자가 결과를 보고 판단해야 하는 성격의 작업이다.
 */
@Service
public class ReconciliationLauncher {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLauncher.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 실행 결과. 위반 상세는 `reconciliation_result` 테이블에 남는다. */
    public record RunResult(String jobStatus, String exitCode, String runDate, String requestedAt) {
    }

    private final JobLauncher launcher;
    private final Job reconciliationJob;

    public ReconciliationLauncher(JobLauncher launcher,
                                  @Qualifier(BatchJobNames.DAILY_RECONCILIATION) Job reconciliationJob) {
        this.launcher = launcher;
        this.reconciliationJob = reconciliationJob;
    }

    @Auditable(action = "BATCH_RECONCILIATION_RUN", targetType = "BATCH_JOB")
    public RunResult runNow() {
        String requestedAt = Instant.now().toString();
        JobParameters parameters = new JobParametersBuilder()
                .addString("runDate", LocalDate.now(KST).toString(), true)
                // 매 호출을 새 JobInstance로 만든다 — 이게 없으면 하루 두 번째부터 거부된다
                .addString("requestedAt", requestedAt, true)
                .toJobParameters();

        try {
            JobExecution execution = launcher.run(reconciliationJob, parameters);
            log.info("수동 대사 실행 완료: status={} exit={}",
                    execution.getStatus(), execution.getExitStatus().getExitCode());
            return new RunResult(execution.getStatus().name(),
                    execution.getExitStatus().getExitCode(),
                    LocalDate.now(KST).toString(), requestedAt);
        } catch (Exception e) {
            throw new IllegalStateException("대사 배치를 실행하지 못했습니다: " + e.getMessage(), e);
        }
    }
}

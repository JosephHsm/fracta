package com.fracta.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.fracta.batch.infrastructure.BatchJobScheduler;

class BatchSchedulingConfigurationTest {

    @Test
    @DisplayName("6개 Job 스케줄이 Phase 9 사양대로 선언된다")
    void schedulesAreDeclared() throws Exception {
        assertCron("reconcileDaily", "${batch.schedule.reconciliation:0 0 23 * * *}");
        assertCron("verifyChainDaily", "${batch.schedule.chain-verification:0 30 23 * * *}");
        assertCron("reportSettlementDaily", "${batch.schedule.settlement-report:0 0 18 * * *}");
        assertCron("refreshBrokerToken", "${batch.schedule.broker-token-refresh:0 0/30 * * * *}");
        assertCron("archiveApiLogsWeekly", "${batch.schedule.api-log-archive:0 0 2 * * SUN}");

        Method allotment = BatchJobScheduler.class.getMethod("allotDueSubscriptions");
        assertThat(allotment.getAnnotation(Scheduled.class).fixedDelayString())
                .isEqualTo("${batch.schedule.allotment-scan-delay:60000}");
    }

    private void assertCron(String methodName, String expected) throws Exception {
        Method method = BatchJobScheduler.class.getMethod(methodName);
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo(expected);
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}

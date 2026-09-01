package com.fracta.batch.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fracta.batch.api.BatchAlertEvent;

/** Phase 9 범위의 ADMIN 알림 대체: 이벤트를 구조화 로그로 소비한다. */
@Component
public class BatchAlertLogger {

    private static final Logger log = LoggerFactory.getLogger(BatchAlertLogger.class);

    @EventListener
    public void onAlert(BatchAlertEvent event) {
        log.error("ADMIN 배치 경보: severity={} job={} executionId={} invariant={} symbol={} detail={}",
                event.severity(), event.jobName(), event.jobExecutionId(), event.invariantCode(),
                event.tokenSymbol(), event.detail());
    }
}

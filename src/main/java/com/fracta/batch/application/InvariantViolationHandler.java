package com.fracta.batch.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.fracta.batch.api.BatchAlertEvent;
import com.fracta.issuance.api.ListedTokenPort;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 불변식 위반 공통 처리: 거래 중단 + 경보 이벤트 + 메트릭. 자동 복구는 하지 않는다. */
@Service
public class InvariantViolationHandler {

    private static final Logger log = LoggerFactory.getLogger(InvariantViolationHandler.class);

    private final ListedTokenPort listedTokens;
    private final ApplicationEventPublisher events;
    private final Counter violationCounter;

    public InvariantViolationHandler(ListedTokenPort listedTokens,
                                     ApplicationEventPublisher events,
                                     MeterRegistry meterRegistry) {
        this.listedTokens = listedTokens;
        this.events = events;
        this.violationCounter = Counter.builder("fracta.ledger.invariant.violation")
                .description("배치가 검출한 원장 불변식 위반 수")
                .tag("source", "batch")
                .register(meterRegistry);
    }

    public void handle(String jobName, long jobExecutionId, ReconciliationCheck check) {
        if (check.valid()) {
            return;
        }
        violationCounter.increment();
        String reason = "%s 위반: %s".formatted(check.invariantCode(), check.detail());

        if (ReconciliationCheck.GLOBAL_SCOPE.equals(check.tokenSymbol())) {
            listedTokens.listAll().stream()
                    .filter(ListedTokenPort.ListedToken::tradable)
                    .forEach(token -> listedTokens.suspend(token.tokenSymbol(), reason));
        } else {
            listedTokens.findByTokenSymbol(check.tokenSymbol())
                    .filter(ListedTokenPort.ListedToken::tradable)
                    .ifPresent(token -> listedTokens.suspend(token.tokenSymbol(), reason));
        }

        log.error("배치 불변식 위반: job={} executionId={} invariant={} symbol={} detail={}",
                jobName, jobExecutionId, check.invariantCode(), check.tokenSymbol(), check.detail());
        events.publishEvent(new BatchAlertEvent(jobName, "CRITICAL", check.invariantCode(),
                check.tokenSymbol(), check.detail(), jobExecutionId, Instant.now()));
    }
}

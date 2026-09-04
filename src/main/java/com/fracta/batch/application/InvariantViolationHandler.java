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

/**
 * 불변식 위반 공통 처리: 경보 이벤트 + 메트릭, 그리고 <b>심각도에 맞는 만큼만</b> 거래 중단.
 * 자동 복구는 하지 않는다.
 *
 * <p>중단 범위는 {@link ReconciliationCheck#severity()}가 정한다. 전역 집계 불일치
 * (INV-3 현금, INV-6)는 경보만 올린다 — 예전에는 이것도 전 종목을 정지시켰는데, 집계 항이
 * 하나 빠지거나 검증 스냅샷이 어긋나기만 해도 플랫폼 전체가 멈추는 구조였다.
 *
 * <p>"자동 복구하지 않는다"는 원칙과의 정합도 여기서 맞는다. 거래 중단은 그 자체로
 * 전 주문 취소·잠금 해제·증거금 환급이라는 대규모 자동 쓰기다. 훼손이 확인되지 않은
 * 상태에서 그걸 실행하는 건 원칙에 어긋난다.
 */
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
        ReconciliationCheck.Severity severity = check.severity();

        switch (severity) {
            case HALT_ALL -> suspendAll(reason);
            case HALT_TOKEN -> suspendOne(check.tokenSymbol(), reason);
            case ALERT -> log.error("""
                    전역 집계 불일치라 자동 중단하지 않는다 — 경보만 올린다. \
                    중단 판단은 사람이 한다 (POST /api/v1/admin/tokens/{{symbol}}/suspend): {}""", reason);
        }

        log.error("배치 불변식 위반: job={} executionId={} invariant={} symbol={} severity={} detail={}",
                jobName, jobExecutionId, check.invariantCode(), check.tokenSymbol(),
                severity, check.detail());
        events.publishEvent(new BatchAlertEvent(jobName, "CRITICAL", check.invariantCode(),
                check.tokenSymbol(), check.detail(), jobExecutionId, Instant.now()));
    }

    /** 원장 자체가 훼손됐다 — 전 종목을 멈춘다. */
    private void suspendAll(String reason) {
        listedTokens.listAll().stream()
                .filter(ListedTokenPort.ListedToken::tradable)
                .forEach(token -> listedTokens.suspend(token.tokenSymbol(), reason));
    }

    private void suspendOne(String tokenSymbol, String reason) {
        listedTokens.findByTokenSymbol(tokenSymbol)
                .filter(ListedTokenPort.ListedToken::tradable)
                .ifPresent(token -> listedTokens.suspend(token.tokenSymbol(), reason));
    }
}

package com.fracta.external.broker.plug;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fracta.external.broker.MarketDataPort;

/**
 * 무중단 폴링 내구 검증용 프로브 (phase-05 완료 조건: "무중단 폴링 성공 · 쿼터 초과 0건").
 *
 * <p>{@code broker.probe.enabled=true} 일 때만 뜬다. 운영 기본값은 꺼짐이다.
 * 결과는 주기적으로 로그에 요약되고 {@link #snapshot()} 으로도 읽을 수 있다.
 */
@Component
@ConditionalOnProperty(name = "broker.probe.enabled", havingValue = "true")
public class PlugEndurancePoller {

    private static final Logger log = LoggerFactory.getLogger(PlugEndurancePoller.class);

    public record Snapshot(Duration elapsed, long calls, long success, long quotaRejected,
                           long otherErrors, long tokenIssues) {
    }

    private final MarketDataPort marketData;
    private final PlugTokenManager tokenManager;
    private final String ticker;

    private final Instant startedAt = Instant.now();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong quotaRejected = new AtomicLong();
    private final AtomicLong otherErrors = new AtomicLong();

    private final long tokenIssuesAtStart;

    public PlugEndurancePoller(MarketDataPort marketData, PlugTokenManager tokenManager,
                               @Value("${broker.probe.ticker:005930}") String ticker) {
        this.marketData = marketData;
        this.tokenManager = tokenManager;
        this.ticker = ticker;
        this.tokenIssuesAtStart = tokenManager.issueCount();
        log.info("증권사 내구 폴링 프로브 시작 — ticker={}", ticker);
    }

    @Scheduled(fixedDelayString = "${broker.probe.interval-millis:1000}")
    public void poll() {
        calls.incrementAndGet();
        try {
            marketData.getCurrentPrice(ticker);
            success.incrementAndGet();
        } catch (BrokerApiException e) {
            if (e.category() == BrokerApiException.Category.RATE_LIMIT) {
                quotaRejected.incrementAndGet();
            } else {
                otherErrors.incrementAndGet();
            }
        } catch (Exception e) {
            otherErrors.incrementAndGet();
        }
    }

    @Scheduled(fixedDelayString = "${broker.probe.report-millis:60000}")
    public void report() {
        Snapshot s = snapshot();
        log.info("PROBE elapsed={}s calls={} success={} quota={} other={} tokenIssues={}",
                s.elapsed().toSeconds(), s.calls(), s.success(), s.quotaRejected(),
                s.otherErrors(), s.tokenIssues());
    }

    public Snapshot snapshot() {
        return new Snapshot(Duration.between(startedAt, Instant.now()),
                calls.get(), success.get(), quotaRejected.get(), otherErrors.get(),
                tokenManager.issueCount() - tokenIssuesAtStart);
    }
}

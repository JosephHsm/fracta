package com.fracta.trading.application;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fracta.common.money.Money;
import com.fracta.external.broker.MarketDataPort;
import com.fracta.external.broker.PriceConverter;
import com.fracta.issuance.api.ListedTokenPort;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 괴리율 산출·경보 (TR-07, TR-08).
 *
 * <pre>
 * |괴리율| &gt; 경고 임계치  → 경고 (로그 + 메트릭). 거래는 계속된다
 * |괴리율| &gt; 중단 임계치  → 해당 종목 자동 SUSPENDED
 * </pre>
 *
 * <p>임계치는 <b>종목별 재정의 → 설정 기본값</b>(기본 10% / 20%) 순으로 정해진다.
 * REIT·ETF·부동산의 정상 괴리 범위가 같을 리 없는데 한 값으로 고정돼 있었다.
 *
 * <p>원자산 티커가 없거나 시세를 못 구하면 <b>계산 자체를 건너뛴다</b> — 0으로 나누기를 막고,
 * Mock 어댑터만으로도 동작해야 하기 때문이다.
 */
@Component
public class PremiumRateMonitor {

    private static final Logger log = LoggerFactory.getLogger(PremiumRateMonitor.class);

    /** 한 건의 판정 결과. 호출자가 임계치를 다시 계산하지 않게 그대로 돌려준다. */
    public enum Verdict { NORMAL, WARNED, SUSPENDED }

    /** 종목별 재정의가 없을 때 쓰는 기본 임계치. */
    private final BigDecimal defaultWarnThreshold;
    private final BigDecimal defaultSuspendThreshold;

    private final MarketDataPort marketData;
    private final ListedTokenPort listedTokens;
    private final Counter warnings;
    private final Counter suspensions;

    public PremiumRateMonitor(MarketDataPort marketData, ListedTokenPort listedTokens,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${trading.premium.warn-percent:10}") BigDecimal warnPercent,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${trading.premium.suspend-percent:20}") BigDecimal suspendPercent,
                              MeterRegistry meterRegistry) {
        if (warnPercent.signum() <= 0 || suspendPercent.signum() <= 0) {
            throw new IllegalArgumentException("괴리율 임계치는 양수여야 한다");
        }
        if (warnPercent.compareTo(suspendPercent) > 0) {
            throw new IllegalArgumentException("경고 임계치가 중단 임계치보다 클 수 없다");
        }
        this.defaultWarnThreshold = warnPercent;
        this.defaultSuspendThreshold = suspendPercent;
        this.marketData = marketData;
        this.listedTokens = listedTokens;
        this.warnings = Counter.builder("fracta.trading.premium.warning")
                .description("괴리율 10% 초과 경고 수").register(meterRegistry);
        this.suspensions = Counter.builder("fracta.trading.premium.suspended")
                .description("괴리율 20% 초과로 인한 자동 거래 중단 수").register(meterRegistry);
    }

    /**
     * 체결가 기준 괴리율. 계산 불가면 비어 있는 값 — 호출자는 {@code premium_rate}에 null을 저장한다.
     *
     * <p>종목 정보는 호출자가 이미 읽은 것을 넘긴다. 체결마다 다시 조회하면 매칭 처리량이 떨어진다.
     */
    public Optional<BigDecimal> calculate(ListedTokenPort.ListedToken token, Money executedPrice) {
        if (token == null || token.brokerTicker() == null || token.brokerTicker().isBlank()) {
            return Optional.empty();
        }
        try {
            Money underlying = marketData.getCurrentPrice(token.brokerTicker()).price();
            return PriceConverter.premiumRate(executedPrice, underlying, token.splitRatio());
        } catch (RuntimeException e) {
            // 증권사 장애가 체결을 막아서는 안 된다 — 괴리율만 건너뛴다
            log.warn("괴리율 계산 생략 (시세 조회 실패): symbol={} 사유={}",
                    token.tokenSymbol(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 임계치 판정 후 필요하면 거래를 중단시킨다. 임계치는 종목별 재정의 → 설정 기본값 순이다.
     *
     * @return 거래 중단이 발생했으면 true
     */
    public boolean evaluate(String tokenSymbol, BigDecimal premiumRate) {
        return evaluate(listedTokens.findByTokenSymbol(tokenSymbol).orElse(null),
                tokenSymbol, premiumRate, true) == Verdict.SUSPENDED;
    }

    /**
     * 임계치 판정. 종목 정보를 이미 들고 있으면 이 쪽을 쓴다 — 재조회하지 않는다.
     *
     * @param allowSuspend false 면 중단 임계치를 넘어도 경보만 남긴다. 장 시간 외처럼
     *                     기준 시세를 믿기 어려운 상황에서 되돌리기 비싼 조치를 미루기 위한 것이다
     * @return 이번 판정 결과
     */
    public Verdict evaluate(ListedTokenPort.ListedToken token, String tokenSymbol,
                            BigDecimal premiumRate, boolean allowSuspend) {
        BigDecimal magnitude = premiumRate.abs();
        BigDecimal suspendAt = thresholdOf(token == null ? null : token.premiumSuspendPercent(),
                defaultSuspendThreshold);
        BigDecimal warnAt = thresholdOf(token == null ? null : token.premiumWarnPercent(),
                defaultWarnThreshold);

        if (magnitude.compareTo(suspendAt) > 0) {
            if (!allowSuspend) {
                warnings.increment();
                log.warn("괴리율 {}% — 중단 임계치({}%) 초과지만 기준 시세를 신뢰할 수 없어 경보만 남긴다: {}",
                        premiumRate, suspendAt, tokenSymbol);
                return Verdict.WARNED;
            }
            suspensions.increment();
            log.error("괴리율 {}% — {}% 초과로 자동 거래 중단: {}", premiumRate, suspendAt, tokenSymbol);
            listedTokens.suspend(tokenSymbol,
                    "괴리율 %s%% (%s%% 초과)".formatted(premiumRate, suspendAt));
            return Verdict.SUSPENDED;
        }
        if (magnitude.compareTo(warnAt) > 0) {
            warnings.increment();
            log.warn("괴리율 {}% — {}% 초과 경고 (거래는 계속된다): {}", premiumRate, warnAt, tokenSymbol);
            return Verdict.WARNED;
        }
        return Verdict.NORMAL;
    }

    private static BigDecimal thresholdOf(BigDecimal override, BigDecimal fallback) {
        return override == null ? fallback : override;
    }
}

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
 * |괴리율| &gt; 10%  → 경고 (로그 + 메트릭). 거래는 계속된다
 * |괴리율| &gt; 20%  → 해당 종목 자동 SUSPENDED
 * </pre>
 *
 * <p>원자산 티커가 없거나 시세를 못 구하면 <b>계산 자체를 건너뛴다</b> — 0으로 나누기를 막고,
 * Mock 어댑터만으로도 동작해야 하기 때문이다.
 */
@Component
public class PremiumRateMonitor {

    private static final Logger log = LoggerFactory.getLogger(PremiumRateMonitor.class);

    static final BigDecimal WARN_THRESHOLD = new BigDecimal("10");
    static final BigDecimal SUSPEND_THRESHOLD = new BigDecimal("20");

    private final MarketDataPort marketData;
    private final ListedTokenPort listedTokens;
    private final Counter warnings;
    private final Counter suspensions;

    public PremiumRateMonitor(MarketDataPort marketData, ListedTokenPort listedTokens,
                              MeterRegistry meterRegistry) {
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
     * 임계치 판정 후 필요하면 거래를 중단시킨다.
     *
     * @return 거래 중단이 발생했으면 true
     */
    public boolean evaluate(String tokenSymbol, BigDecimal premiumRate) {
        BigDecimal magnitude = premiumRate.abs();
        if (magnitude.compareTo(SUSPEND_THRESHOLD) > 0) {
            suspensions.increment();
            log.error("괴리율 {}% — 20% 초과로 자동 거래 중단: {}", premiumRate, tokenSymbol);
            listedTokens.suspend(tokenSymbol, "괴리율 %s%% (20%% 초과)".formatted(premiumRate));
            return true;
        }
        if (magnitude.compareTo(WARN_THRESHOLD) > 0) {
            warnings.increment();
            log.warn("괴리율 {}% — 10% 초과 경고 (거래는 계속된다): {}", premiumRate, tokenSymbol);
        }
        return false;
    }
}

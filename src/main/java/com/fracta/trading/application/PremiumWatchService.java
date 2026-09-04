package com.fracta.trading.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.common.money.Money;
import com.fracta.external.broker.MarketSessionTracker;
import com.fracta.external.broker.MarketVenue;
import com.fracta.issuance.api.ListedTokenPort;
import com.fracta.trading.domain.TradeExecution;
import com.fracta.trading.infrastructure.TradeExecutionRepository;

/**
 * 체결과 무관하게 도는 괴리율 감시 (TR-08 보강).
 *
 * <p><b>왜 필요한가.</b> 괴리율 판정이 체결 시점에만 붙어 있으면 <b>거래가 없는 종목은
 * 괴리가 아무리 벌어져도 감지되지 않는다.</b> 유동성이 얇은 조각투자에서 그건 예외가 아니라
 * 흔한 상태이고, 감시가 가장 필요한 순간(조용한 종목의 원자산이 급변하는 때)이 정확히
 * 사각지대가 된다.
 *
 * <p>기준가는 <b>마지막 체결가</b>다. 플랫폼에 현재가라는 개념이 따로 없으므로, 마지막으로
 * 시장이 합의한 가격과 지금 원자산 환산가의 거리를 본다. 체결 이력이 없는 종목은 비교 대상이
 * 없어 건너뛴다.
 *
 * <p>어느 거래소도 열려 있지 않으면 <b>중단시키지 않고 경보만</b> 남긴다. 그때 쓰는 원자산
 * 시세는 마지막 종가라, 자체 오더북이 24시간 열려 있는 지금 구조에서는 새벽 체결 하나로 자동
 * 중단이 걸릴 수 있다. 되돌리는 비용이 큰 조치를 신뢰할 수 없는 기준가로 실행하지 않는다.
 *
 * <p>개장 여부는 {@link MarketSessionTracker} 가 거래소 응답으로 판정한다 — 시간표가 아니다.
 */
@Service
public class PremiumWatchService {

    private static final Logger log = LoggerFactory.getLogger(PremiumWatchService.class);

    /** 한 주기 결과. */
    public record WatchReport(int examined, int skipped, int warned, int suspended) {
    }

    private final ListedTokenPort listedTokens;
    private final TradeExecutionRepository executions;
    private final PremiumRateMonitor monitor;
    private final MarketSessionTracker sessions;
    private final boolean suspendOutsideMarketHours;

    public PremiumWatchService(ListedTokenPort listedTokens, TradeExecutionRepository executions,
                               PremiumRateMonitor monitor, MarketSessionTracker sessions,
                               @Value("${trading.premium.suspend-outside-market-hours:false}")
                               boolean suspendOutsideMarketHours) {
        this.listedTokens = listedTokens;
        this.executions = executions;
        this.monitor = monitor;
        this.sessions = sessions;
        this.suspendOutsideMarketHours = suspendOutsideMarketHours;
    }

    @Scheduled(fixedDelayString = "${trading.premium.watch-interval-millis:300000}")
    public void scheduledSweep() {
        try {
            WatchReport report = sweep();
            if (report.warned() > 0 || report.suspended() > 0) {
                log.warn("괴리율 감시 — 검사 {}건 경고 {}건 중단 {}건",
                        report.examined(), report.warned(), report.suspended());
            }
        } catch (RuntimeException e) {
            // 증권사 장애나 일시적 오류가 스케줄러 자체를 멈추게 두지 않는다
            log.warn("괴리율 감시 실패 — 다음 주기에 재시도한다", e);
        }
    }

    /** 상장 종목을 한 바퀴 돌며 마지막 체결가 기준 괴리율을 본다. */
    @Transactional(readOnly = true)
    public WatchReport sweep() {
        // 거래소가 알려준 장 상태로 판단한다. 시간표를 코드에 박으면 공휴일·조기폐장을 놓치고,
        // 해외 종목은 서머타임까지 있어 아예 맞출 수 없다.
        boolean anyOpen = sessions.anyOpenVenue().isPresent();
        boolean allowSuspend = anyOpen || suspendOutsideMarketHours;
        int examined = 0;
        int skipped = 0;
        int warned = 0;
        int suspended = 0;

        for (ListedTokenPort.ListedToken token : listedTokens.listAll()) {
            if (!token.tradable()) {
                continue;
            }
            Optional<Money> lastPrice = lastExecutionPrice(token.tokenSymbol());
            if (lastPrice.isEmpty()) {
                skipped++;   // 체결 이력이 없으면 비교할 플랫폼 가격이 없다
                continue;
            }
            Optional<BigDecimal> premium = monitor.calculate(token, lastPrice.get());
            if (premium.isEmpty()) {
                skipped++;   // 티커가 없거나 시세 조회 실패 — 오류가 아니다
                continue;
            }
            examined++;
            switch (monitor.evaluate(token, token.tokenSymbol(), premium.get(), allowSuspend)) {
                case SUSPENDED -> suspended++;
                case WARNED -> warned++;
                case NORMAL -> { }
            }
        }
        return new WatchReport(examined, skipped, warned, suspended);
    }

    private Optional<Money> lastExecutionPrice(String tokenSymbol) {
        List<TradeExecution> latest = executions
                .findByTokenSymbolOrderByIdDesc(tokenSymbol, PageRequest.of(0, 1))
                .getContent();
        return latest.isEmpty() ? Optional.empty() : Optional.of(Money.of(latest.getFirst().price()));
    }
}

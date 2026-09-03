package com.fracta.trading.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fracta.common.money.Money;
import com.fracta.external.broker.Candle;
import com.fracta.external.broker.MarketDataPort;
import com.fracta.external.broker.PriceConverter;
import com.fracta.issuance.api.ListedTokenPort;

/**
 * 종목 상세의 기초자산 시세 차트 (FSD §11 종목 상세 필수 요소).
 *
 * <p><b>서버가 조각 참조가로 환산해서 내려준다.</b> 원자산 가격(예: 100,000원)을 그대로 주고
 * 화면에서 분할비율로 나누게 하면, 프론트가 금액을 재계산하게 된다 — 금지 사항이다.
 * 반올림 규칙이 서버와 화면에서 갈리면 차트와 호가창의 기준선이 어긋난다.
 *
 * <p>증권사 티커가 없는 종목은 빈 목록을 준다. 차트가 없는 것과 오류는 다르다.
 */
@Service
public class MarketChartService {

    private static final Logger log = LoggerFactory.getLogger(MarketChartService.class);

    /** 조각 참조가로 환산된 일봉. 금액 단위는 원(최소단위 정수). */
    public record ReferenceCandle(LocalDate date, long open, long high, long low, long close,
                                  long volume) {
    }

    public record Chart(String tokenSymbol, String brokerTicker, long splitRatio,
                        List<ReferenceCandle> candles) {
    }

    /** 거래일 기준은 KST다. UTC로 잡으면 장 마감 직후 하루가 밀린다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ListedTokenPort listedTokens;
    private final MarketDataPort marketData;
    private final Clock clock;

    @Autowired
    public MarketChartService(ListedTokenPort listedTokens, MarketDataPort marketData) {
        this(listedTokens, marketData, Clock.system(KST));
    }

    /** 테스트에서 기준일을 고정하기 위한 생성자 (MarketHours와 같은 방식). */
    MarketChartService(ListedTokenPort listedTokens, MarketDataPort marketData, Clock clock) {
        this.listedTokens = listedTokens;
        this.marketData = marketData;
        this.clock = clock;
    }

    public Chart dailyChart(String tokenSymbol, int days) {
        var token = listedTokens.findByTokenSymbol(tokenSymbol)
                .orElseThrow(() -> new IllegalArgumentException("종목이 없다: " + tokenSymbol));

        if (token.brokerTicker() == null || token.brokerTicker().isBlank()) {
            return new Chart(tokenSymbol, null, token.splitRatio(), List.of());
        }

        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);
        try {
            List<ReferenceCandle> candles = marketData
                    .getDailyCandles(token.brokerTicker(), from, to).stream()
                    .map(candle -> toReference(candle, token.splitRatio()))
                    .toList();
            return new Chart(tokenSymbol, token.brokerTicker(), token.splitRatio(), candles);
        } catch (RuntimeException e) {
            // 증권사 장애가 종목 화면 전체를 막아서는 안 된다 — 차트만 비운다.
            log.warn("일봉 조회 실패, 차트를 비운다: symbol={} ticker={} 사유={}",
                    tokenSymbol, token.brokerTicker(), e.getMessage());
            return new Chart(tokenSymbol, token.brokerTicker(), token.splitRatio(), List.of());
        }
    }

    private static ReferenceCandle toReference(Candle candle, long splitRatio) {
        return new ReferenceCandle(candle.date(),
                reference(candle.open(), splitRatio),
                reference(candle.high(), splitRatio),
                reference(candle.low(), splitRatio),
                reference(candle.close(), splitRatio),
                candle.volume());
    }

    private static long reference(Money underlying, long splitRatio) {
        return PriceConverter.referencePrice(underlying, splitRatio).amount();
    }
}

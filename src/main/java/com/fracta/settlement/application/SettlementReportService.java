package com.fracta.settlement.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.trading.infrastructure.TradeExecutionRepository;

/**
 * 정산 리포트 집계 (ST-04). 배치 스케줄은 Phase 9이고 여기서는 집계 로직만 제공한다.
 */
@Service
public class SettlementReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record DailyReport(LocalDate date, long executionCount, long executionAmount,
                              long buyFee, long sellFee) {

        public long totalFee() {
            return buyFee + sellFee;
        }
    }

    private final TradeExecutionRepository executions;

    public SettlementReportService(TradeExecutionRepository executions) {
        this.executions = executions;
    }

    @Transactional(readOnly = true)
    public DailyReport dailyReport(LocalDate date) {
        Instant from = date.atStartOfDay(KST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KST).toInstant();

        List<Object[]> rows = executions.aggregateBetween(from, to);
        Object[] row = rows.isEmpty() ? new Object[]{0L, 0L, 0L, 0L} : rows.getFirst();
        return new DailyReport(date,
                asLong(row[0]), asLong(row[1]), asLong(row[2]), asLong(row[3]));
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}

package com.fracta.settlement.api;

import java.time.LocalDate;

/** 배치 모듈에 공개하는 일별 정산 집계 포트. */
public interface SettlementReportPort {

    DailyReport dailyReport(LocalDate date);

    record DailyReport(LocalDate date, long executionCount, long executionAmount,
                       long buyFee, long sellFee) {

        public long totalFee() {
            return Math.addExact(buyFee, sellFee);
        }
    }
}

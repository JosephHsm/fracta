package com.fracta.trading.infrastructure;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.trading.domain.TradeExecution;

public interface TradeExecutionRepository extends JpaRepository<TradeExecution, Long> {

    Page<TradeExecution> findByTokenSymbolOrderByIdDesc(String tokenSymbol, Pageable pageable);

    List<TradeExecution> findByTokenSymbolOrderByIdDesc(String tokenSymbol);

    /** 일별 정산 집계 (ST-04) — 건수·체결금액·수수료. */
    @Query("""
            SELECT COUNT(e), COALESCE(SUM(e.price * e.units), 0),
                   COALESCE(SUM(e.buyFee), 0), COALESCE(SUM(e.sellFee), 0)
            FROM TradeExecution e
            WHERE e.executedAt >= :from AND e.executedAt < :to
            """)
    List<Object[]> aggregateBetween(@Param("from") Instant from, @Param("to") Instant to);
}

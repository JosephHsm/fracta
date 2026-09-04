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

    /**
     * 이 투자자가 한쪽 당사자인 체결 전부. 취득원가 재생에 쓴다.
     *
     * <p>한 체결에 매수·매도 두 당사자가 있으므로 양쪽을 각각 조인해 UNION 한다.
     * 수수료도 자기 쪽 것만 가져온다 — 매수 수수료는 취득원가에 들어가고 매도 수수료는 아니다.
     *
     * <p>반환 컬럼: token_symbol, price, units, fee, side, executed_at
     */
    @Query(value = """
            SELECT e.token_symbol, e.price, e.units, e.buy_fee AS fee, 'BUY' AS side, e.executed_at
              FROM trade_execution e
              JOIN trade_order o ON o.id = e.buy_order_id
             WHERE o.investor_id = :investorId
            UNION ALL
            SELECT e.token_symbol, e.price, e.units, e.sell_fee AS fee, 'SELL' AS side, e.executed_at
              FROM trade_execution e
              JOIN trade_order o ON o.id = e.sell_order_id
             WHERE o.investor_id = :investorId
             ORDER BY 6
            """, nativeQuery = true)
    List<Object[]> findLotsOfInvestor(@Param("investorId") long investorId);

    /** 마지막 체결가 — 평가 기준가 1순위. 체결이 없으면 비어 있다. */
    @Query(value = """
            SELECT e.price FROM trade_execution e
             WHERE e.token_symbol = :tokenSymbol
             ORDER BY e.id DESC
             LIMIT 1
            """, nativeQuery = true)
    java.util.Optional<Long> findLastPrice(@Param("tokenSymbol") String tokenSymbol);
}

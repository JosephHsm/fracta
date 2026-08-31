package com.fracta.trading.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.TradeOrder;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {

    Optional<TradeOrder> findByIdempotencyKey(String idempotencyKey);

    /**
     * 체결량 반영을 단일 UPDATE로 처리한다. 엔티티를 읽어 고치면 주문 1건마다 SELECT+UPDATE가
     * 두 번씩 늘어나 매칭 처리량이 떨어진다.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = """
            UPDATE trade_order
               SET filled_units = filled_units + :units,
                   status = CASE WHEN filled_units + :units >= units THEN 'FILLED'
                                 ELSE 'PARTIALLY_FILLED' END,
                   version = version + 1
             WHERE id = :id AND filled_units + :units <= units
            """, nativeQuery = true)
    int applyFill(@Param("id") long id, @Param("units") long units);

    List<TradeOrder> findByInvestorIdOrderByIdDesc(long investorId);

    /** 오더북 복원: 미체결 주문을 시간 우선순위 그대로 읽는다. */
    @Query("""
            SELECT o FROM TradeOrder o
            WHERE o.status IN :statuses
            ORDER BY o.createdAt ASC, o.id ASC
            """)
    List<TradeOrder> findOpenOrders(@Param("statuses") List<OrderStatus> statuses);

    @Query("""
            SELECT o FROM TradeOrder o
            WHERE o.tokenSymbol = :symbol AND o.status IN :statuses
            ORDER BY o.createdAt ASC, o.id ASC
            """)
    List<TradeOrder> findOpenOrdersOfSymbol(@Param("symbol") String symbol,
                                            @Param("statuses") List<OrderStatus> statuses);
}

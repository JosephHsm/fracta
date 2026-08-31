package com.fracta.trading.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.common.money.Units;
import com.fracta.ledger.api.LedgerPort;
import com.fracta.ledger.api.OwnerId;
import com.fracta.trading.domain.OrderSide;
import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.TradeOrder;
import com.fracta.trading.infrastructure.TradeOrderRepository;

/**
 * 오더북 재시작 복원 (FSD §8.4 필수).
 *
 * <p>오더북은 인메모리라 재시작하면 사라진다. 복원하지 않으면 미체결 주문이 유실되고,
 * 원장에는 잠금이 남은 채 주문만 사라져 잠금이 영구히 풀리지 않는다.
 *
 * <p>{@code created_at} 오름차순으로 다시 넣어 <b>시간 우선순위를 보존</b>한다.
 * 복원 후 오더북의 매도 잠금 수량과 원장 {@code locked_units}가 일치하는지 검증한다.
 */
@Component
public class OrderBookRestorer {

    private static final Logger log = LoggerFactory.getLogger(OrderBookRestorer.class);

    public record RestoreReport(int restoredOrders, int symbols, List<String> mismatches) {

        public boolean consistent() {
            return mismatches.isEmpty();
        }
    }

    private final TradeOrderRepository orders;
    private final OrderBookExecutor executor;
    private final LedgerPort ledger;

    public OrderBookRestorer(TradeOrderRepository orders, OrderBookExecutor executor, LedgerPort ledger) {
        this.orders = orders;
        this.executor = executor;
        this.ledger = ledger;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        RestoreReport report = restore();
        if (!report.consistent()) {
            // 부팅을 막는 대신 경보를 올린다 — 정합성 불일치를 조용히 넘기지 않는다
            log.error("오더북 복원 후 잠금 정합성 불일치 {}건: {}",
                    report.mismatches().size(), report.mismatches());
        }
    }

    @Transactional(readOnly = true)
    public RestoreReport restore() {
        List<TradeOrder> open = orders.findOpenOrders(
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED));

        // created_at 오름차순으로 넣어야 동가 시간 우선순위가 보존된다
        for (TradeOrder order : open) {
            executor.runOnPartition(order.tokenSymbol(), book -> book.restore(order.toBookOrder()));
        }

        List<String> mismatches = verifyLocks(open);
        List<String> symbols = open.stream().map(TradeOrder::tokenSymbol).distinct().toList();
        log.info("오더북 복원 완료 — 주문 {}건 / 종목 {}개 / 잠금 불일치 {}건",
                open.size(), symbols.size(), mismatches.size());
        return new RestoreReport(open.size(), symbols.size(), mismatches);
    }

    /** 오더북의 매도 미체결 잔량 합 == 원장 locked_units 여야 한다. */
    private List<String> verifyLocks(List<TradeOrder> open) {
        record Key(String symbol, long investorId) {
        }
        Map<Key, Long> bookLocked = new HashMap<>();
        for (TradeOrder order : open) {
            if (order.side() == OrderSide.SELL) {
                bookLocked.merge(new Key(order.tokenSymbol(), order.investorId()),
                        order.remaining(), Long::sum);
            }
        }

        List<String> mismatches = new ArrayList<>();
        bookLocked.forEach((key, expected) -> {
            Units ledgerLocked = ledger.balanceOf(key.symbol(), OwnerId.of(key.investorId())).lockedUnits();
            if (ledgerLocked.value() != expected) {
                mismatches.add("symbol=%s investor=%d 오더북=%d 원장=%d"
                        .formatted(key.symbol(), key.investorId(), expected, ledgerLocked.value()));
            }
        });
        return mismatches;
    }
}

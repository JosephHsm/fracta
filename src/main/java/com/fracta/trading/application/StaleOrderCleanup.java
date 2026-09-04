package com.fracta.trading.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.trading.domain.OrderStatus;
import com.fracta.trading.domain.TradeOrder;
import com.fracta.trading.infrastructure.TradeOrderRepository;

/**
 * 오래된 미체결 주문 정리 (TR-03 확장).
 *
 * <p><b>왜 필요한가.</b> 주문에 유효기간이 없어 미체결 잔량이 영원히 호가창에 남았다. 남아 있는
 * 동안 매도는 원장 수량을, 매수는 예치금을 계속 묶는다 — 몇 달 전에 걸어 두고 잊은 주문이
 * 자산을 잠그고 있는 상태가 정상으로 취급됐다. 호가창도 실제로 체결될 생각이 없는 주문으로
 * 채워져 깊이가 부풀려진다.
 *
 * <p>기본은 {@code trading.order-ttl:P7D}(7일)다. 만료된 주문은 <b>취소</b>로 처리한다 —
 * 거절이 아니다. 주문에 잘못이 있어서가 아니라 유효기간이 다한 것이고, 취소 경로가 이미
 * 잠금·홀드를 정확히 되돌린다.
 *
 * <p>{@code trading.order-ttl:0} 이면 정리하지 않는다(무기한). 테스트·데모 기본값이다.
 */
@Service
public class StaleOrderCleanup {

    private static final Logger log = LoggerFactory.getLogger(StaleOrderCleanup.class);

    /** 한 주기에 정리할 상한. 밀린 걸 한 번에 다 처리하려 들지 않는다. */
    static final int SWEEP_LIMIT = 200;

    private final TradeOrderRepository orders;
    private final TradingService trading;
    private final Duration ttl;

    public StaleOrderCleanup(TradeOrderRepository orders, TradingService trading,
                             @Value("${trading.order-ttl:0s}") Duration ttl) {
        this.orders = orders;
        this.trading = trading;
        this.ttl = ttl;
    }

    @Scheduled(fixedDelayString = "${trading.order-ttl-sweep-millis:600000}")
    public void scheduledSweep() {
        try {
            int expired = sweep();
            if (expired > 0) {
                log.info("유효기간이 지난 미체결 주문 {}건을 취소했다", expired);
            }
        } catch (RuntimeException e) {
            // 일시적 오류가 스케줄러 자체를 멈추게 두지 않는다
            log.warn("만료 주문 정리 실패 — 다음 주기에 재시도한다", e);
        }
    }

    /**
     * 만료된 미체결 주문을 취소한다.
     *
     * @return 취소한 건수
     */
    @Transactional(readOnly = true)
    public int sweep() {
        if (ttl.isZero() || ttl.isNegative()) {
            return 0;   // 무기한 — 정리하지 않는다
        }
        Instant cutoff = Instant.now().minus(ttl);
        List<TradeOrder> stale = orders.findStaleOpenOrders(
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED), cutoff,
                org.springframework.data.domain.Limit.of(SWEEP_LIMIT));

        int cancelled = 0;
        for (TradeOrder order : stale) {
            try {
                // 취소 경로를 그대로 탄다 — 오더북 제거·잠금 해제·홀드 환급이 이미 여기 있다
                trading.cancelExpired(order.id(), ttl);
                cancelled++;
            } catch (RuntimeException e) {
                // 한 건이 실패해도 나머지는 정리한다
                log.warn("만료 주문 취소 실패: orderId={} 사유={}", order.id(), e.getMessage());
            }
        }
        return cancelled;
    }
}

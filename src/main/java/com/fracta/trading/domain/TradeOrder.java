package com.fracta.trading.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "trade_order")
public class TradeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_symbol", nullable = false)
    private String tokenSymbol;

    @Column(name = "investor_id", nullable = false)
    private long investorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private OrderType orderType;

    /** 시장가는 null. */
    private Long price;

    @Column(nullable = false)
    private long units;

    @Column(name = "filled_units", nullable = false)
    private long filledUnits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.OPEN;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /**
     * 아직 환급되지 않은 매수 대금 홀드 잔액. 매도는 항상 0이다 (수량을 원장에서 잠근다).
     * 체결마다 그 체결분을 환급하며 줄고, 종료 상태에서 남은 만큼 전액 환급된다.
     */
    @Column(name = "held_amount", nullable = false)
    private long heldAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected TradeOrder() {
    }

    public TradeOrder(String tokenSymbol, long investorId, OrderSide side, OrderType orderType,
                      Long price, long units, String idempotencyKey) {
        this.tokenSymbol = tokenSymbol;
        this.investorId = investorId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.units = units;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }

    public void applyFill(long amount) {
        if (amount <= 0 || filledUnits + amount > units) {
            throw new IllegalStateException(
                    "체결량이 주문 범위를 벗어났다: filled=%d + %d > %d".formatted(filledUnits, amount, units));
        }
        filledUnits += amount;
        status = filledUnits == units ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /** 접수 시점에 잡아 둔 대금 홀드를 기록한다. 실제 차감은 {@code CashPort.holdMargin}이 한다. */
    public void holdFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("홀드 금액은 음수가 될 수 없다: " + amount);
        }
        if (side != OrderSide.BUY) {
            throw new IllegalStateException("매도 주문은 대금을 홀드하지 않는다: " + id);
        }
        this.heldAmount = amount;
    }

    /**
     * 홀드를 그만큼 환급 처리했다고 기록한다. 남은 홀드보다 크게 요청하면 남은 만큼만 줄인다 —
     * 환급은 실제 원장 이동을 동반하므로 반환값(실제 환급액)을 반드시 그대로 써야 한다.
     *
     * @return 실제로 환급해야 하는 금액
     */
    public long releaseHold(long amount) {
        long released = Math.min(Math.max(amount, 0), heldAmount);
        heldAmount -= released;
        return released;
    }

    /** 남은 홀드를 전부 환급 처리한다. 종료 상태로 갈 때 호출한다. */
    public long releaseAllHold() {
        return releaseHold(heldAmount);
    }

    public long heldAmount() {
        return heldAmount;
    }

    /** 미체결 잔량 취소. 이미 체결된 부분은 되돌리지 않는다. */
    public void cancel() {
        if (!status.isOpenOnBook()) {
            throw new IllegalStateException("취소할 수 없는 상태다: " + status);
        }
        status = OrderStatus.CANCELLED;
    }

    public void reject() {
        status = OrderStatus.REJECTED;
    }

    public long remaining() {
        return units - filledUnits;
    }

    public Long id() {
        return id;
    }

    public String tokenSymbol() {
        return tokenSymbol;
    }

    public long investorId() {
        return investorId;
    }

    public OrderSide side() {
        return side;
    }

    public OrderType orderType() {
        return orderType;
    }

    public Long price() {
        return price;
    }

    public long units() {
        return units;
    }

    public long filledUnits() {
        return filledUnits;
    }

    public OrderStatus status() {
        return status;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public BookOrder toBookOrder() {
        return new BookOrder(id, investorId, side, orderType, price, units, filledUnits, createdAt);
    }
}

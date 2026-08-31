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

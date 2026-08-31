package com.fracta.trading.domain;

import java.time.Instant;

/**
 * 오더북에 올라가는 주문. 인메모리 전용 가변 객체이며 잔량만 변한다.
 * 종목별 단일 스레드에서만 다뤄지므로 동기화가 없다 (FSD §8.4).
 */
public final class BookOrder {

    private final long orderId;
    private final long investorId;
    private final OrderSide side;
    private final OrderType orderType;
    /** 시장가는 null. */
    private final Long price;
    private final long units;
    private final Instant createdAt;

    private long filledUnits;

    public BookOrder(long orderId, long investorId, OrderSide side, OrderType orderType,
                     Long price, long units, long filledUnits, Instant createdAt) {
        if (units <= 0) {
            throw new IllegalArgumentException("주문 수량은 1 이상이어야 한다: " + units);
        }
        if (filledUnits < 0 || filledUnits > units) {
            throw new IllegalArgumentException("체결 수량이 범위를 벗어났다: " + filledUnits);
        }
        if (orderType == OrderType.LIMIT && (price == null || price <= 0)) {
            throw new IllegalArgumentException("지정가 주문에는 가격이 필요하다");
        }
        this.orderId = orderId;
        this.investorId = investorId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.units = units;
        this.filledUnits = filledUnits;
        this.createdAt = createdAt;
    }

    public long orderId() {
        return orderId;
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

    public Instant createdAt() {
        return createdAt;
    }

    public long remaining() {
        return units - filledUnits;
    }

    public boolean isFilled() {
        return remaining() == 0;
    }

    public void fill(long amount) {
        if (amount <= 0 || amount > remaining()) {
            throw new IllegalArgumentException(
                    "체결량이 잔량을 벗어났다: amount=%d remaining=%d".formatted(amount, remaining()));
        }
        filledUnits += amount;
    }

    /** 결제 실패로 체결을 되돌린다. 되돌리지 않으면 오더북 잔량이 DB보다 적어진다. */
    public void revertFill(long amount) {
        if (amount <= 0 || amount > filledUnits) {
            throw new IllegalArgumentException(
                    "되돌릴 체결량이 범위를 벗어났다: amount=%d filled=%d".formatted(amount, filledUnits));
        }
        filledUnits -= amount;
    }
}

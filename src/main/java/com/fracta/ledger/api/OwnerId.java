package com.fracta.ledger.api;

/** 원장 소유자 식별자 (Phase 3부터 investor.id를 가리킨다). */
public record OwnerId(long value) {

    public OwnerId {
        if (value <= 0) {
            throw new IllegalArgumentException("OwnerId는 양수여야 한다: " + value);
        }
    }

    public static OwnerId of(long value) {
        return new OwnerId(value);
    }
}

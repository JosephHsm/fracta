package com.fracta.ledger.api;

import java.util.Objects;

/** 원장 트랜잭션의 출처 참조. refId는 출처 도메인의 식별자(nullable). */
public record TxRef(RefType refType, String refId) {

    public TxRef {
        Objects.requireNonNull(refType, "refType은 필수다");
    }

    public static TxRef of(RefType refType, String refId) {
        return new TxRef(refType, refId);
    }
}

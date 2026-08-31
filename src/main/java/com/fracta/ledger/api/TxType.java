package com.fracta.ledger.api;

/** 원장 트랜잭션 유형 (FSD §5.1). BURN은 스키마에만 존재 — 포트 연산은 추후 Phase에서 추가. */
public enum TxType {
    ISSUE,
    TRANSFER,
    BURN,
    LOCK,
    UNLOCK
}

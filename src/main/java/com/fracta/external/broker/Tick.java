package com.fracta.external.broker;

import java.time.Instant;

import com.fracta.common.money.Money;

/** 실시간 체결 틱. */
public record Tick(String ticker, Money price, long volume, Instant at) {
}

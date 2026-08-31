package com.fracta.external.broker;

import java.time.Instant;

import com.fracta.common.money.Money;

/** 현재가 시세. */
public record Quote(String ticker, Money price, Instant at) {
}

package com.fracta.external.broker;

import java.time.LocalDate;

import com.fracta.common.money.Money;

/** 일봉. */
public record Candle(LocalDate date, Money open, Money high, Money low, Money close, long volume) {
}

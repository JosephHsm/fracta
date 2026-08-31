package com.fracta.account.api;

/** 투자자 식별자. */
public record InvestorId(long value) {

    public InvestorId {
        if (value <= 0) {
            throw new IllegalArgumentException("InvestorId는 양수여야 한다: " + value);
        }
    }

    public static InvestorId of(long value) {
        return new InvestorId(value);
    }
}

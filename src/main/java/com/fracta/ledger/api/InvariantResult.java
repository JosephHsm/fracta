package com.fracta.ledger.api;

import java.util.List;

/** 불변식(INV-1~INV-3) 검증 결과. violations가 비어 있으면 valid. */
public record InvariantResult(String tokenSymbol, boolean valid, List<String> violations) {

    public static InvariantResult of(String tokenSymbol, List<String> violations) {
        return new InvariantResult(tokenSymbol, violations.isEmpty(), List.copyOf(violations));
    }
}

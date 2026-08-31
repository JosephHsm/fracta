package com.fracta.ledger.api;

import com.fracta.common.money.Units;

/** 소유자·토큰별 잔고. 가용 수량 = 총 수량 − 잠긴 수량. */
public record Balance(OwnerId owner, String tokenSymbol, Units units, Units lockedUnits) {

    public static Balance zero(OwnerId owner, String tokenSymbol) {
        return new Balance(owner, tokenSymbol, Units.ZERO, Units.ZERO);
    }

    public Units available() {
        return units.minus(lockedUnits);
    }
}

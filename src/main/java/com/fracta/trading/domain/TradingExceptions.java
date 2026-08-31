package com.fracta.trading.domain;

import java.util.Map;

import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

/** 유통 모듈 도메인 예외 모음. */
public final class TradingExceptions {

    private TradingExceptions() {
    }

    /** 거래 가능 상태(LISTED)가 아닌 종목에 주문. */
    public static class NotTradableException extends DomainException {
        public NotTradableException(String tokenSymbol, String status) {
            super(ErrorCode.STATE_NOT_TRADABLE,
                    "거래 가능한 종목이 아니다. 현재 상태: " + status,
                    Map.of("tokenSymbol", tokenSymbol, "status", status));
        }
    }

    /** 취소 불가 상태. */
    public static class OrderNotCancellableException extends DomainException {
        public OrderNotCancellableException(long orderId, OrderStatus status) {
            super(ErrorCode.STATE_INVALID_TRANSITION,
                    "취소할 수 없는 주문이다. 현재 상태: " + status,
                    Map.of("orderId", orderId, "status", status.name()));
        }
    }

    /** 본인 주문이 아님. */
    public static class ForbiddenOrderAccessException extends DomainException {
        public ForbiddenOrderAccessException(long orderId) {
            super(ErrorCode.AUTH_FORBIDDEN, "본인의 주문만 처리할 수 있다",
                    Map.of("orderId", orderId));
        }
    }

    /** 시장가 주문인데 반대편 호가가 없어 체결 불가. */
    public static class NoLiquidityException extends DomainException {
        public NoLiquidityException(String tokenSymbol) {
            super(ErrorCode.STATE_NO_LIQUIDITY,
                    "반대편 호가가 없어 시장가 주문을 체결할 수 없다",
                    Map.of("tokenSymbol", tokenSymbol));
        }
    }
}

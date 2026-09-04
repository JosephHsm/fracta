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
                    "거래 가능한 종목이 아닙니다. 현재 상태: " + status,
                    Map.of("tokenSymbol", tokenSymbol, "status", status));
        }
    }

    /** 취소 불가 상태. */
    public static class OrderNotCancellableException extends DomainException {
        public OrderNotCancellableException(long orderId, OrderStatus status) {
            super(ErrorCode.STATE_INVALID_TRANSITION,
                    "취소할 수 없는 주문입니다. 현재 상태: " + status,
                    Map.of("orderId", orderId, "status", status.name()));
        }
    }

    /** 본인 주문이 아님. */
    public static class ForbiddenOrderAccessException extends DomainException {
        public ForbiddenOrderAccessException(long orderId) {
            super(ErrorCode.AUTH_FORBIDDEN, "본인의 주문만 처리할 수 있습니다.",
                    Map.of("orderId", orderId));
        }
    }

    /**
     * 이미 쓴 멱등성 키가 다른 요청에 다시 왔다 — 다른 투자자거나 주문 내용이 다르다.
     *
     * <p>재생(replay)은 <b>같은 사람이 같은 주문을 다시 보냈을 때만</b> 성립한다.
     * 확인 없이 돌려주면 남의 주문 상태가 새어 나가고, 내용이 달라도 최초 결과를 돌려줘
     * 클라이언트는 넣지도 않은 주문이 들어간 줄 안다.
     */
    public static class IdempotencyConflictException extends DomainException {
        public IdempotencyConflictException(String idempotencyKey) {
            super(ErrorCode.IDEM_KEY_CONFLICT,
                    "동일 Idempotency-Key가 다른 요청에 이미 사용됐습니다.",
                    Map.of("idempotencyKey", idempotencyKey));
        }
    }

    /** 시장가 주문인데 반대편 호가가 없어 체결 불가. */
    public static class NoLiquidityException extends DomainException {
        public NoLiquidityException(String tokenSymbol) {
            super(ErrorCode.STATE_NO_LIQUIDITY,
                    "반대편 호가가 없어 시장가 주문을 체결할 수 없습니다.",
                    Map.of("tokenSymbol", tokenSymbol));
        }
    }

    /** 차트 조회 구간이 허용 범위를 벗어났다. */
    public static class InvalidCandleRangeException extends DomainException {
        public InvalidCandleRangeException(int days, int min, int max) {
            super(ErrorCode.VALID_INVALID_INPUT,
                    "조회 기간은 %d일 이상 %d일 이하여야 합니다.".formatted(min, max),
                    Map.of("days", days, "min", min, "max", max));
        }
    }
}

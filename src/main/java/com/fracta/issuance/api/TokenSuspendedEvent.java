package com.fracta.issuance.api;

/**
 * 종목 거래 중단 이벤트 (TR-08). 유통 모듈이 구독해 미체결 주문을 정리한다.
 * 커밋 후에 발행되어야 한다 — 롤백된 정지로 주문을 취소하면 안 된다.
 */
public record TokenSuspendedEvent(String tokenSymbol, String reason) {
}

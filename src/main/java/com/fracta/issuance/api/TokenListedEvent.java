package com.fracta.issuance.api;

/** 신규 상장 이벤트. 오픈 API가 구독해 {@code token.listed} 웹훅으로 내보낸다. */
public record TokenListedEvent(long issuanceId, String tokenSymbol, long totalUnits, long unitPrice) {
}

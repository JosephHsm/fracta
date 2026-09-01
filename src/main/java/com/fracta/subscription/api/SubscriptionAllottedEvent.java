package com.fracta.subscription.api;

/** 투자자별 배정 확정 이벤트. 오픈 API가 해당 소유자의 웹훅으로만 내보낸다. */
public record SubscriptionAllottedEvent(long orderId, long issuanceId, String tokenSymbol,
                                        long investorId, long allottedUnits) {
}

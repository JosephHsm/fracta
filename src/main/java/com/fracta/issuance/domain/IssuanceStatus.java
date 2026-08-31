package com.fracta.issuance.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 발행 상태 머신 (FSD §5.2). 전이 규칙은 이 enum이 단일 진실 — 서비스에 if를 흩뿌리지 않는다.
 */
public enum IssuanceStatus {

    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    SUBSCRIBING,
    ALLOTTING,
    LISTED,
    SUSPENDED,
    DELISTED,
    REJECTED;

    private static final Map<IssuanceStatus, Set<IssuanceStatus>> ALLOWED = new EnumMap<>(IssuanceStatus.class);

    static {
        ALLOWED.put(DRAFT, Set.of(PENDING_APPROVAL));
        ALLOWED.put(PENDING_APPROVAL, Set.of(APPROVED, REJECTED));
        ALLOWED.put(APPROVED, Set.of(SUBSCRIBING));
        ALLOWED.put(SUBSCRIBING, Set.of(ALLOTTING));
        ALLOWED.put(ALLOTTING, Set.of(LISTED));
        ALLOWED.put(LISTED, Set.of(SUSPENDED, DELISTED));
        ALLOWED.put(SUSPENDED, Set.of(LISTED, DELISTED));
        ALLOWED.put(DELISTED, Set.of());
        ALLOWED.put(REJECTED, Set.of());
    }

    public boolean canTransitionTo(IssuanceStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<IssuanceStatus> allowedTargets() {
        return ALLOWED.get(this);
    }
}

package com.fracta.issuance;

import static com.fracta.issuance.domain.IssuanceStatus.ALLOTTING;
import static com.fracta.issuance.domain.IssuanceStatus.APPROVED;
import static com.fracta.issuance.domain.IssuanceStatus.DELISTED;
import static com.fracta.issuance.domain.IssuanceStatus.DRAFT;
import static com.fracta.issuance.domain.IssuanceStatus.LISTED;
import static com.fracta.issuance.domain.IssuanceStatus.PENDING_APPROVAL;
import static com.fracta.issuance.domain.IssuanceStatus.REJECTED;
import static com.fracta.issuance.domain.IssuanceStatus.SUBSCRIBING;
import static com.fracta.issuance.domain.IssuanceStatus.SUSPENDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fracta.common.error.ErrorCode;
import com.fracta.issuance.domain.InvalidStateTransitionException;
import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;

/** 상태 머신 불변식 (FSD §5.2) — 전이 행렬 전수 검증. */
class IssuanceStateInvariantTest {

    private static final Map<IssuanceStatus, Set<IssuanceStatus>> EXPECTED = Map.of(
            DRAFT, Set.of(PENDING_APPROVAL),
            PENDING_APPROVAL, Set.of(APPROVED, REJECTED),
            APPROVED, Set.of(SUBSCRIBING),
            SUBSCRIBING, Set.of(ALLOTTING),
            ALLOTTING, Set.of(LISTED),
            LISTED, Set.of(SUSPENDED, DELISTED),
            SUSPENDED, Set.of(LISTED, DELISTED),
            DELISTED, Set.of(),
            REJECTED, Set.of());

    @Test
    @DisplayName("전이 행렬 전수 검증 — 허용 목록 외 전이는 모두 불가")
    void transitionMatrix() {
        for (IssuanceStatus from : IssuanceStatus.values()) {
            for (IssuanceStatus to : IssuanceStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("허용되지 않은 전이는 STATE_INVALID_TRANSITION 예외")
    void invalidTransitionThrows() {
        Issuance issuance = new Issuance(1, "FR-TEST-001", 100, 100,
                Instant.now(), Instant.now().plusSeconds(3600));
        assertThat(issuance.status()).isEqualTo(DRAFT);

        assertThatThrownBy(() -> issuance.transitionTo(APPROVED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .satisfies(e -> assertThat(((InvalidStateTransitionException) e).errorCode())
                        .isEqualTo(ErrorCode.STATE_INVALID_TRANSITION));

        // 정상 경로는 통과
        issuance.transitionTo(PENDING_APPROVAL);
        issuance.transitionTo(APPROVED);
        issuance.transitionTo(SUBSCRIBING);
        issuance.transitionTo(ALLOTTING);
        issuance.transitionTo(LISTED);
        issuance.transitionTo(SUSPENDED);
        issuance.transitionTo(LISTED);
        issuance.transitionTo(DELISTED);

        assertThatThrownBy(() -> issuance.transitionTo(LISTED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}

package com.fracta.subscription.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.subscription.domain.SubscriptionOrder;

public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, Long> {

    Optional<SubscriptionOrder> findByIdempotencyKey(String idempotencyKey);

    List<SubscriptionOrder> findByIssuanceIdAndStatusOrderByIdAsc(long issuanceId, SubscriptionOrder.Status status);

    List<SubscriptionOrder> findByInvestorIdOrderByIdDesc(long investorId);

    /** 배정 확정된 주문의 배정 총합 (INV-5). */
    @Query("""
            SELECT COALESCE(SUM(o.allottedUnits), 0) FROM SubscriptionOrder o
            WHERE o.issuanceId = :issuanceId AND o.allottedUnits IS NOT NULL
            """)
    long sumAllottedUnits(@Param("issuanceId") long issuanceId);

    /** 배정 확정 대상이었던(취소 제외) 주문의 신청 총합. */
    @Query("""
            SELECT COALESCE(SUM(o.requestedUnits), 0) FROM SubscriptionOrder o
            WHERE o.issuanceId = :issuanceId AND o.status <> :excluded
            """)
    long sumRequestedUnitsExcluding(@Param("issuanceId") long issuanceId,
                                    @Param("excluded") SubscriptionOrder.Status excluded);

    /** 특정 상태 주문의 증거금 총합 — DEPOSITED로 호출하면 미결제 증거금 (INV-6). */
    @Query("SELECT COALESCE(SUM(o.depositAmount), 0) FROM SubscriptionOrder o WHERE o.status = :status")
    long sumDepositAmountByStatus(@Param("status") SubscriptionOrder.Status status);

    /** INV-6 위반 시 원인 추적용 — 투자자별 미결제 증거금. */
    @Query("""
            SELECT o.investorId, COALESCE(SUM(o.depositAmount), 0) FROM SubscriptionOrder o
            WHERE o.status = :status GROUP BY o.investorId
            """)
    List<Object[]> outstandingMarginByInvestor(@Param("status") SubscriptionOrder.Status status);
}

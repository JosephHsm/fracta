package com.fracta.account.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.account.domain.Investor;

public interface InvestorRepository extends JpaRepository<Investor, Long> {

    Optional<Investor> findByEmail(String email);

    /**
     * 원자적 입금.
     *
     * <p>{@code clearAutomatically}는 쓰지 않는다 — 영속성 컨텍스트를 비우면 호출자가 들고 있던
     * 엔티티가 detach되어 이후 변경이 유실된다(배정 확정 루프에서 실제로 발생했다).
     * 갱신 후 잔액은 {@link #findCashBalance}로 DB에서 다시 읽는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Investor i SET i.cashBalance = i.cashBalance + :amount WHERE i.id = :id")
    int deposit(@Param("id") long id, @Param("amount") long amount);

    /** 원자적 출금 — 잔액 부족 시 0행 갱신. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Investor i SET i.cashBalance = i.cashBalance - :amount
            WHERE i.id = :id AND i.cashBalance >= :amount
            """)
    int withdraw(@Param("id") long id, @Param("amount") long amount);

    /** 스칼라 조회 — 영속성 컨텍스트 캐시를 거치지 않아 벌크 갱신 직후에도 최신값이다. */
    @Query("SELECT i.cashBalance FROM Investor i WHERE i.id = :id")
    Optional<Long> findCashBalance(@Param("id") long id);

    @Query("SELECT COALESCE(SUM(i.cashBalance), 0) FROM Investor i")
    long sumCashBalance();

    /** INV-6 위반 시 원인 추적용 — 투자자별 (id, 외부 순유입, 현재 잔액). */
    @Query(value = """
            SELECT i.id,
                   COALESCE((SELECT SUM(CASE WHEN c.tx_type = 'DEPOSIT' THEN c.amount ELSE -c.amount END)
                             FROM cash_transaction c
                             WHERE c.investor_id = i.id AND c.tx_type IN ('DEPOSIT', 'WITHDRAW')), 0),
                   i.cash_balance
            FROM investor i
            """, nativeQuery = true)
    List<Object[]> cashPositions();
}

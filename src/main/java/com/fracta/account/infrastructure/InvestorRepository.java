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

    /** INV-3 — 음수 현금 잔고는 존재해서는 안 된다. */
    @Query("SELECT COUNT(i) FROM Investor i WHERE i.cashBalance < 0")
    long countNegativeCashBalance();

    /**
     * INV-6 위반 시 원인 추적용 — 투자자별 (id, 외부 순유입, 현재 잔액, 기록으로 설명되지 않는 차액).
     *
     * <p>마지막 값은 {@code 잔액 − Σ(모든 대금 이동 기록)} 이다. 0이 아니면 <b>기록 없이 잔액이
     * 변한 것</b>이라 어느 코드 경로가 장부를 빠뜨렸는지 바로 좁혀진다.
     */
    @Query(value = """
            SELECT i.id,
                   COALESCE((SELECT SUM(CASE WHEN c.tx_type = 'DEPOSIT' THEN c.amount ELSE -c.amount END)
                             FROM cash_transaction c
                             WHERE c.investor_id = i.id AND c.tx_type IN ('DEPOSIT', 'WITHDRAW')), 0),
                   i.cash_balance,
                   i.cash_balance - COALESCE((
                       SELECT SUM(CASE WHEN c.tx_type IN ('DEPOSIT', 'MARGIN_REFUND',
                                                          'SETTLEMENT_CREDIT', 'TRADE_CREDIT', 'FEE_INCOME')
                                       THEN c.amount ELSE -c.amount END)
                       FROM cash_transaction c WHERE c.investor_id = i.id), 0)
            FROM investor i
            """, nativeQuery = true)
    List<Object[]> cashPositions();
}

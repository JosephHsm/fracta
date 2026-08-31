package com.fracta.account.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.account.domain.Investor;

public interface InvestorRepository extends JpaRepository<Investor, Long> {

    Optional<Investor> findByEmail(String email);

    /** 원자적 입금. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Investor i SET i.cashBalance = i.cashBalance + :amount WHERE i.id = :id")
    int deposit(@Param("id") long id, @Param("amount") long amount);

    /** 원자적 출금 — 잔액 부족 시 0행 갱신. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Investor i SET i.cashBalance = i.cashBalance - :amount
            WHERE i.id = :id AND i.cashBalance >= :amount
            """)
    int withdraw(@Param("id") long id, @Param("amount") long amount);

    @Query("SELECT COALESCE(SUM(i.cashBalance), 0) FROM Investor i")
    long sumCashBalance();
}

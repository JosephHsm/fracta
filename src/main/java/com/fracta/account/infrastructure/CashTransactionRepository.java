package com.fracta.account.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.account.domain.CashTransaction;

public interface CashTransactionRepository extends JpaRepository<CashTransaction, Long> {

    List<CashTransaction> findByInvestorIdOrderByIdDesc(long investorId);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashTransaction c WHERE c.txType = :type")
    long sumByType(@Param("type") CashTransaction.Type type);
}

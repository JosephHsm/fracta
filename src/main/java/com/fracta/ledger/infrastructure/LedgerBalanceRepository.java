package com.fracta.ledger.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerBalanceRepository extends JpaRepository<LedgerBalanceEntity, Long> {

    Optional<LedgerBalanceEntity> findByOwnerIdAndTokenSymbol(long ownerId, String tokenSymbol);
}

package com.fracta.issuance.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;

public interface IssuanceRepository extends JpaRepository<Issuance, Long> {

    Optional<Issuance> findTopByTokenSymbolStartingWithOrderByTokenSymbolDesc(String prefix);

    List<Issuance> findByStatusAndSubscriptionStartAtLessThanEqual(IssuanceStatus status, Instant now);
}

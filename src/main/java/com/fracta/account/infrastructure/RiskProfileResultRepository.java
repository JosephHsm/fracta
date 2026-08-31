package com.fracta.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fracta.account.domain.RiskProfileResult;

public interface RiskProfileResultRepository extends JpaRepository<RiskProfileResult, Long> {
}

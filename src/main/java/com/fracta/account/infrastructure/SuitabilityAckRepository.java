package com.fracta.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fracta.account.domain.SuitabilityAck;

public interface SuitabilityAckRepository extends JpaRepository<SuitabilityAck, Long> {

    /** 서명한 등급 이하의 상품을 커버한다 — productGrade 이상 서명 존재 여부. */
    boolean existsByInvestorIdAndProductGradeGreaterThanEqual(long investorId, int productGrade);
}

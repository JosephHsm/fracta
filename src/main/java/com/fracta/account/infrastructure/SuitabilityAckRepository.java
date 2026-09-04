package com.fracta.account.infrastructure;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.account.api.SuitabilityScope;
import com.fracta.account.domain.SuitabilityAck;

public interface SuitabilityAckRepository extends JpaRepository<SuitabilityAck, Long> {

    /**
     * 이 상품에 유효한 서명이 있는가.
     *
     * <p>세 조건을 모두 만족해야 한다 — <b>같은 상품</b>에 대한 서명이고, <b>아직 만료되지
     * 않았고</b>, <b>현재 성향 진단보다 나중에</b> 서명됐어야 한다. 마지막 조건이 재진단
     * 무효화를 대신한다: 등급이 더 보수적으로 바뀌었는데 옛 서명이 살아 있는 게 제일 위험하다.
     *
     * <p>서명 등급이 상품 등급 이상이어야 한다. 4등급 상품에 서명해 두고 더 위험한 5등급을
     * 사는 걸 막는다 — 서명은 자기가 확인한 위험 수준까지만 커버한다.
     */
    @Query("""
            SELECT COUNT(a) > 0 FROM SuitabilityAck a
            WHERE a.investorId = :investorId
              AND a.scopeType = :scopeType
              AND a.scopeId = :scopeId
              AND a.productGrade >= :productGrade
              AND a.expiresAt > :now
              AND a.createdAt >= :profileIssuedAt
            """)
    boolean hasValidAck(@Param("investorId") long investorId,
                        @Param("scopeType") SuitabilityScope.Type scopeType,
                        @Param("scopeId") String scopeId,
                        @Param("productGrade") int productGrade,
                        @Param("now") Instant now,
                        @Param("profileIssuedAt") Instant profileIssuedAt);
}

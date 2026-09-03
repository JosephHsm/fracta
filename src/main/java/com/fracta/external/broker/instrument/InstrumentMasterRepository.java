package com.fracta.external.broker.instrument;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InstrumentMasterRepository extends JpaRepository<InstrumentMaster, String> {

    /**
     * 이름·코드 부분 검색. 네이티브 쿼리를 쓰지 않고 바인딩 파라미터만 쓴다.
     *
     * <p>접두 일치를 먼저 보여준다 — "삼성"을 치면 "삼성전자"가 "국제삼성"보다 위에 와야 한다.
     */
    @Query("""
            SELECT i FROM InstrumentMaster i
             WHERE (:kind IS NULL OR i.assetKind = :kind)
               AND (LOWER(i.korName) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(i.engName) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR i.code LIKE CONCAT(:q, '%'))
             ORDER BY
               CASE WHEN i.code = :q THEN 0
                    WHEN LOWER(i.korName) LIKE LOWER(CONCAT(:q, '%')) THEN 1
                    ELSE 2 END,
               i.marketCap DESC NULLS LAST,
               i.korName
            """)
    List<InstrumentMaster> search(@Param("q") String query,
                                  @Param("kind") AssetKind kind,
                                  Pageable pageable);

    long countByAssetKind(AssetKind assetKind);
}

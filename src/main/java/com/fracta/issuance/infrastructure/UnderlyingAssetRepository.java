package com.fracta.issuance.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fracta.issuance.domain.UnderlyingAsset;

public interface UnderlyingAssetRepository extends JpaRepository<UnderlyingAsset, Long> {

    boolean existsByAssetCode(String assetCode);

    @org.springframework.data.jpa.repository.Query("SELECT a.issuerId FROM UnderlyingAsset a WHERE a.id = :assetId")
    long findIssuerId(@org.springframework.data.repository.query.Param("assetId") long assetId);
}

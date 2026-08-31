package com.fracta.issuance.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fracta.issuance.domain.UnderlyingAsset;

public interface UnderlyingAssetRepository extends JpaRepository<UnderlyingAsset, Long> {

    boolean existsByAssetCode(String assetCode);
}

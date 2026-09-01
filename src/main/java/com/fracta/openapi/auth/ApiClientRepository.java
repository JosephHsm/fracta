package com.fracta.openapi.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {

    Optional<ApiClient> findByClientId(String clientId);

    List<ApiClient> findByOwnerInvestorId(long ownerInvestorId);
}

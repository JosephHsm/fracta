package com.fracta.openapi.log;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLog, Long> {

    List<ApiCallLog> findByClientIdOrderByIdDesc(String clientId);

    long countByClientId(String clientId);
}

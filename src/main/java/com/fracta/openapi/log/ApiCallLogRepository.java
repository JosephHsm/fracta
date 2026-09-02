package com.fracta.openapi.log;

import java.util.List;
import java.time.Instant;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLog, Long> {

    List<ApiCallLog> findByClientIdOrderByIdDesc(String clientId);

    long countByClientId(String clientId);

    long countByClientIdAndCalledAtGreaterThanEqual(String clientId, Instant from);

    long countByClientIdAndStatusCodeGreaterThanEqualAndCalledAtGreaterThanEqual(
            String clientId, int statusCode, Instant from);

    @Query("""
            SELECT l FROM ApiCallLog l
             WHERE l.clientId = :clientId
               AND (:endpoint = '' OR LOWER(l.endpoint) LIKE LOWER(CONCAT('%', :endpoint, '%')))
               AND (:statusCode = -1 OR l.statusCode = :statusCode)
               AND l.calledAt >= :from
               AND l.calledAt <= :to
             ORDER BY l.id DESC
            """)
    List<ApiCallLog> search(@Param("clientId") String clientId,
                            @Param("endpoint") String endpoint,
                            @Param("statusCode") int statusCode,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);

    @Query("""
            SELECT l FROM ApiCallLog l
             WHERE l.clientId = :clientId AND l.calledAt >= :from
             ORDER BY l.id ASC
            """)
    List<ApiCallLog> findTimeline(@Param("clientId") String clientId,
                                  @Param("from") Instant from);
}

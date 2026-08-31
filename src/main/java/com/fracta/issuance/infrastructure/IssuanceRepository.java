package com.fracta.issuance.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fracta.issuance.domain.Issuance;
import com.fracta.issuance.domain.IssuanceStatus;

import jakarta.persistence.LockModeType;

public interface IssuanceRepository extends JpaRepository<Issuance, Long> {

    Optional<Issuance> findTopByTokenSymbolStartingWithOrderByTokenSymbolDesc(String prefix);

    Optional<Issuance> findByTokenSymbol(String tokenSymbol);

    List<Issuance> findByStatusAndSubscriptionStartAtLessThanEqual(IssuanceStatus status, Instant now);

    /** 방식 A — 비관적 락 (FSD §8.3). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Issuance i WHERE i.id = :id")
    Optional<Issuance> findByIdForUpdate(@Param("id") Long id);

    /**
     * 방식 C — DB 원자적 감소. status 조건 필수 (청약 종료 후 감소 방지).
     *
     * <p>{@code clearAutomatically}는 쓰지 않는다 — 컨텍스트를 비우면 호출자가 들고 있던
     * 엔티티가 detach되어 이후 변경이 유실된다(청약 취소의 상태 변경이 실제로 사라졌다).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE issuance SET remaining_units = remaining_units - :units
            WHERE id = :id AND status = 'SUBSCRIBING' AND remaining_units >= :units
            """, nativeQuery = true)
    int atomicReserve(@Param("id") long id, @Param("units") long units);

    /** 취소 시 잔여 수량 복구. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE issuance SET remaining_units = remaining_units + :units
            WHERE id = :id AND status = 'SUBSCRIBING' AND remaining_units + :units <= total_units
            """, nativeQuery = true)
    int atomicRelease(@Param("id") long id, @Param("units") long units);

    /** 스칼라 조회 — 네이티브 갱신 직후에도 캐시를 거치지 않고 최신값을 읽는다. */
    @Query(value = "SELECT remaining_units FROM issuance WHERE id = :id", nativeQuery = true)
    Long findRemainingUnits(@Param("id") long id);
}

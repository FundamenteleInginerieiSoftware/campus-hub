package com.campus.reservation.repository;

import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.ReservationHold;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationHoldRepository extends JpaRepository<ReservationHold, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM ReservationHold h WHERE h.id = :id")
    Optional<ReservationHold> findByIdForUpdate(@Param("id") Long id);

    List<ReservationHold> findByUserIdAndStatusAndExpiresAtAfter(Long userId, HoldStatus status, LocalDateTime now);

    long countByUserIdAndStatusAndExpiresAtAfter(Long userId, HoldStatus status, LocalDateTime now);

    @Query("""
        SELECT COUNT(h) > 0 FROM ReservationHold h
        WHERE h.assetId = :assetId
          AND h.status = 'ACTIVE'
          AND h.expiresAt > :now
          AND (:excludeHoldId IS NULL OR h.id != :excludeHoldId)
          AND (:excludeUserId IS NULL OR h.userId != :excludeUserId)
          AND h.startTime < :endTime
          AND h.endTime > :startTime
    """)
    boolean existsActiveOverlap(
            @Param("assetId") Long assetId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("now") LocalDateTime now,
            @Param("excludeHoldId") Long excludeHoldId,
            @Param("excludeUserId") Long excludeUserId);

    @Query("""
        SELECT DISTINCT h.assetId FROM ReservationHold h
        WHERE h.status = 'ACTIVE'
          AND h.expiresAt > :now
          AND (:excludeUserId IS NULL OR h.userId != :excludeUserId)
          AND h.startTime < :endTime
          AND h.endTime > :startTime
    """)
    List<Long> findHeldAssetIds(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("now") LocalDateTime now,
            @Param("excludeUserId") Long excludeUserId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ReservationHold h
        SET h.status = 'EXPIRED'
        WHERE h.status = 'ACTIVE'
          AND h.expiresAt <= :now
    """)
    int expireOldHolds(@Param("now") LocalDateTime now);

    // Cooldown check (#240 point 4): verifies if the student has recently acquired a hold that overlaps
    // with the requested window on this asset, preventing script loops that vary start/end slightly.
    @Query("""
        SELECT COUNT(h) > 0 FROM ReservationHold h
        WHERE h.userId = :userId
          AND h.assetId = :assetId
          AND h.startTime < :endTime
          AND h.endTime > :startTime
          AND h.createdAt > :threshold
    """)
    boolean existsRecentHoldOverlap(
            @Param("userId") Long userId,
            @Param("assetId") Long assetId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("threshold") LocalDateTime threshold);
}

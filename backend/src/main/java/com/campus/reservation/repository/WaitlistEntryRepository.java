package com.campus.reservation.repository;

import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.id = :id")
    Optional<WaitlistEntry> findByIdForUpdate(@Param("id") Long id);

    // FIFO ordering under row locks so a concurrent student cancel waits instead of tripping @Version (#349).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT w FROM WaitlistEntry w
        WHERE w.assetId = :assetId
          AND w.status = :status
        ORDER BY w.createdAt ASC, w.id ASC
        """)
    List<WaitlistEntry> findByAssetIdAndStatusForUpdate(
            @Param("assetId") Long assetId, @Param("status") WaitlistStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT w FROM WaitlistEntry w
        WHERE w.userId = :userId
          AND w.status = :status
        ORDER BY w.createdAt ASC, w.id ASC
        """)
    List<WaitlistEntry> findByUserIdAndStatusForUpdate(
            @Param("userId") Long userId, @Param("status") WaitlistStatus status);

    Page<WaitlistEntry> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    long countByUserIdAndStatus(Long userId, WaitlistStatus status);

    boolean existsByAssetIdAndUserIdAndStartTimeAndEndTimeAndStatus(
            Long assetId, Long userId, LocalDateTime startTime, LocalDateTime endTime, WaitlistStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WaitlistEntry w
        SET w.status = com.campus.reservation.domain.WaitlistStatus.EXPIRED,
            w.updatedAt = :now,
            w.version = w.version + 1
        WHERE w.status = com.campus.reservation.domain.WaitlistStatus.WAITING
          AND w.startTime <= :now
        """)
    int expireOldEntries(@Param("now") LocalDateTime now);
}

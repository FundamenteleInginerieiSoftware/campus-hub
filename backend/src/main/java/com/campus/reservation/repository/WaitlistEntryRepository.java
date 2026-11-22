package com.campus.reservation.repository;

import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.id = :id")
    Optional<WaitlistEntry> findByIdForUpdate(@Param("id") Long id);

    // FIFO ordering: earliest queued request wins when a slot frees up.
    List<WaitlistEntry> findByAssetIdAndStatusOrderByCreatedAtAscIdAsc(Long assetId, WaitlistStatus status);

    List<WaitlistEntry> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    boolean existsByAssetIdAndUserIdAndStartTimeAndEndTimeAndStatus(
            Long assetId, Long userId, LocalDateTime startTime, LocalDateTime endTime, WaitlistStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WaitlistEntry w
        SET w.status = com.campus.reservation.domain.WaitlistStatus.EXPIRED,
            w.updatedAt = :now
        WHERE w.status = com.campus.reservation.domain.WaitlistStatus.WAITING
          AND w.startTime <= :now
        """)
    int expireOldEntries(@Param("now") LocalDateTime now);
}

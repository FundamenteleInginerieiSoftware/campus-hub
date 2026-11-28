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

    // Read-only FIFO peek used by processPromotionsForAsset to pick the next candidate's userId
    // before acquiring locks in global DAG order (Quota -> Asset -> Waitlist -> Hold, #349, #370).
    List<WaitlistEntry> findByAssetIdAndStatusOrderByCreatedAtAscIdAsc(Long assetId, WaitlistStatus status);

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

    // Lock expired rows in FIFO order with SKIP LOCKED so concurrent promotion/disable row locks
    // (ORDER BY created_at ASC, id ASC FOR UPDATE) cannot deadlock (40P01) with the expiry sweep (#349).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                UPDATE waitlist_entries
                SET status = 'EXPIRED',
                    updated_at = :now,
                    version = version + 1
                WHERE id IN (
                    SELECT id
                    FROM waitlist_entries
                    WHERE status = 'WAITING'
                      AND start_time <= :now
                    ORDER BY created_at ASC, id ASC
                    FOR UPDATE SKIP LOCKED
                )
                """, nativeQuery = true)
    int expireOldEntries(@Param("now") LocalDateTime now);
}

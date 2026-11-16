package com.campus.reservation.repository;

import com.campus.reservation.domain.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKeyAndUserId(String idempotencyKey, Long userId);

    // SELECT ... FOR UPDATE serializes concurrent retries of the same FAILED or abandoned IN_PROGRESS
    // row so two threads cannot both read FAILED and restart the same key under READ COMMITTED.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.idempotencyKey = :key AND r.userId = :userId")
    Optional<IdempotencyRecord> findWithLockByIdempotencyKeyAndUserId(
            @Param("key") String idempotencyKey, @Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :cutoff")
    int deleteExpiredRecords(@Param("cutoff") LocalDateTime cutoff);
}

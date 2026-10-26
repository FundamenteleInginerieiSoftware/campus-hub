package com.campus.reservation.repository;

import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {

    @Query("""
        select w from WaitlistEntry w
        where w.assetId = :assetId
          and w.status = com.campus.reservation.domain.WaitlistStatus.WAITING
          and w.startTime > :now
        order by w.createdAt asc, w.id asc
        """)
    List<WaitlistEntry> findWaitingByAssetOrderByFifo(@Param("assetId") Long assetId, @Param("now") LocalDateTime now);

    @Query("""
        select count(w) > 0 from WaitlistEntry w
        where w.userId = :userId
          and w.assetId = :assetId
          and w.status = com.campus.reservation.domain.WaitlistStatus.WAITING
          and w.startTime < :end
          and w.endTime > :start
        """)
    boolean existsOverlappingWaitlistForUser(
            @Param("userId") Long userId,
            @Param("assetId") Long assetId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    List<WaitlistEntry> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<WaitlistEntry> findByStatusOrderByCreatedAtAsc(WaitlistStatus status);
}

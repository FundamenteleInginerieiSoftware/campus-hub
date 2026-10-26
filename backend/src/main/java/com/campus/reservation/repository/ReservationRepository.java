package com.campus.reservation.repository;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Query("""
        select count(r) > 0 from Reservation r
        where r.assetId = :assetId
          and r.status in :statuses
          and r.startTime < :end
          and r.endTime > :start
          and r.id <> :ignoreId
        """)
    boolean existsOverlap(
            @Param("assetId") Long assetId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("ignoreId") long ignoreId);

    long countByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses);

    Page<Reservation> findByUserIdOrderByStartTimeAsc(Long userId, Pageable pageable);

    Page<Reservation> findByUserIdAndStatusOrderByStartTimeAsc(
            Long userId, ReservationStatus status, Pageable pageable);

    Optional<Reservation> findFirstByAssetIdAndStatusIn(Long assetId, Collection<ReservationStatus> statuses);

    @Query("""
        select r from Reservation r
        where r.assetId = :assetId
          and r.status = com.campus.reservation.api.ReservationStatus.CONFIRMED
          and r.endTime > :from
        order by r.startTime asc
        """)
    List<Reservation> findUpcomingConfirmedByAsset(@Param("assetId") Long assetId, @Param("from") LocalDateTime from);

    // Strictly `< :cutoff` (where cutoff = now - 30m) so a student arriving at exact startTime + 30m
    // can still check out (`!now.isAfter(latest)`), and the sweep only marks NO_SHOW after +30m has passed.
    @Query("""
        select r from Reservation r
        where r.status = com.campus.reservation.api.ReservationStatus.CONFIRMED
          and r.startTime < :cutoff
        order by r.startTime asc
        """)
    List<Reservation> findConfirmedBeforeCutoff(@Param("cutoff") LocalDateTime cutoff);

    @Query("""
        select r from Reservation r
        where r.status = com.campus.reservation.api.ReservationStatus.CHECKED_OUT
          and r.endTime < :now
        order by r.endTime asc
        """)
    List<Reservation> findCheckedOutPastEnd(@Param("now") LocalDateTime now);
}

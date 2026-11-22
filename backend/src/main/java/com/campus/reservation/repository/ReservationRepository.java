package com.campus.reservation.repository;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    // Active bookings block slots either over their booked interval (CONFIRMED, CHECKED_OUT)
    // or open-endedly from startTime onward while unreturned (OVERDUE) until marked RETURNED.
    @Query("""
        select count(r) > 0 from Reservation r
        where r.assetId = :assetId
          and r.id <> :ignoreId
          and (
            (r.status in (
                com.campus.reservation.api.ReservationStatus.CONFIRMED,
                com.campus.reservation.api.ReservationStatus.CHECKED_OUT)
              and r.startTime < :end and r.endTime > :start)
            or
            (r.status = com.campus.reservation.api.ReservationStatus.OVERDUE
              and r.startTime < :end)
          )
        """)
    boolean existsOverlap(
            @Param("assetId") Long assetId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("ignoreId") long ignoreId);

    long countByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses);

    // Bug fixed (#240): count distinct booking sessions instead of raw rows.
    // A multi-asset bundle (common bundleId) counts as 1 slot of the student quota (max 2).
    // Individual bookings (bundleId IS NULL) count as 1 slot each.
    @Query("""
        select count(distinct coalesce(cast(r.bundleId as string), cast(r.id as string)))
        from Reservation r
        where r.userId = :userId
          and r.status in :statuses
        """)
    long countBookingSessionsByUserIdAndStatusIn(
            @Param("userId") Long userId, @Param("statuses") Collection<ReservationStatus> statuses);

    // Acquires a transaction-scoped PostgreSQL advisory lock for this student, so two simultaneous
    // booking requests from the same student on different assets wait for each other before counting
    // active bookings (the asset row locks alone don't, because they lock different rows).
    // Wrapped in count(*) only because pg_advisory_xact_lock returns void, which Spring Data cannot map
    // as a result; selecting it inside a subquery gives us a normal number back.
    // Two-key form: key 1 means "reservation quota", key 2 is the user id. The one-key form shares one
    // number space with every other advisory lock in the database, so user 5 would block asset 5 if
    // someone later locks by asset id. The user id is cast to int because the two-key form takes two ints.
    @Query(
            value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(1, CAST(:userId AS integer))) AS quota_lock",
            nativeQuery = true)
    long lockUserQuota(@Param("userId") Long userId);

    long countByUserIdAndOriginalAssetIdIsNotNull(Long userId);

    Page<Reservation> findByUserIdOrderByStartTimeAsc(Long userId, Pageable pageable);

    Page<Reservation> findByUserIdAndStatusOrderByStartTimeAsc(
            Long userId, ReservationStatus status, Pageable pageable);

    Page<Reservation> findByUserIdAndStatusInOrderByStartTimeAsc(
            Long userId, Collection<ReservationStatus> statuses, Pageable pageable);

    Optional<Reservation> findFirstByAssetIdAndStatusInOrderByStartTimeDesc(
            Long assetId, Collection<ReservationStatus> statuses);

    List<Reservation> findByStatus(ReservationStatus status);

    @Query("""
        select r from Reservation r
        where r.assetId = :assetId
          and r.status = com.campus.reservation.api.ReservationStatus.CONFIRMED
          and r.endTime > :from
        order by r.startTime asc
        """)
    List<Reservation> findUpcomingConfirmedByAsset(@Param("assetId") Long assetId, @Param("from") LocalDateTime from);

    @Query("""
        select r from Reservation r
        where r.userId = :userId
          and r.status = com.campus.reservation.api.ReservationStatus.CONFIRMED
          and r.endTime > :cutoff
        order by r.startTime asc
        """)
    List<Reservation> findUpcomingConfirmedByUser(@Param("userId") Long userId, @Param("cutoff") LocalDateTime cutoff);

    // Acquires row locks in ascending id order so concurrent extendReservation or acceptTransfer calls
    // wait instead of tripping @Version and rolling back cancelUpcomingReservationsForUser (#229, #322).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select r from Reservation r
        where r.userId = :userId
          and r.status = com.campus.reservation.api.ReservationStatus.CONFIRMED
          and r.endTime > :cutoff
        order by r.id asc
        """)
    List<Reservation> findUpcomingConfirmedByUserForUpdate(
            @Param("userId") Long userId, @Param("cutoff") LocalDateTime cutoff);

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

    // Uses the same active-status overlap rule as existsOverlap (bounded for CONFIRMED/CHECKED_OUT,
    // open-ended from startTime onward for OVERDUE) so category availability never drifts.
    @Query("""
        select distinct r.assetId from Reservation r
        where (
            (r.status in (
                com.campus.reservation.api.ReservationStatus.CONFIRMED,
                com.campus.reservation.api.ReservationStatus.CHECKED_OUT)
              and r.startTime < :end and r.endTime > :start)
            or
            (r.status = com.campus.reservation.api.ReservationStatus.OVERDUE
              and r.startTime < :end)
          )
        """)
    List<Long> findBusyAssetIds(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<Reservation> findByBundleIdOrderByAssetIdAsc(UUID bundleId);

    // Acquires row locks in ascending id order (same order as findUpcomingConfirmedByUserForUpdate)
    // so concurrent cancelBundle and cancelUpcomingReservationsForUser cannot deadlock or trip STALE_VERSION (#336).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.bundleId = :bundleId ORDER BY r.id ASC")
    List<Reservation> findByBundleIdForUpdate(@Param("bundleId") UUID bundleId);

    // Student cancelBundle variant: locks only the caller's own items in the bundle so items
    // transferred to another student are not locked before ownership filtering (#338 review nit).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.bundleId = :bundleId AND r.userId = :userId ORDER BY r.id ASC")
    List<Reservation> findByBundleIdAndUserIdForUpdate(@Param("bundleId") UUID bundleId, @Param("userId") Long userId);

    @Query("""
        select r from Reservation r
        where r.userId = :userId
          and r.startTime < :to
          and r.endTime > :from
        order by r.startTime asc
        """)
    List<Reservation> findByUserIdOverlappingRange(
            @Param("userId") Long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // Used by ReservationService.usedMinutesByAsset for Stefania's Equipment Reliability feature (#289).
    // Returns only reservations that were physically picked up (checkedOutAt != null) and overlap [from, to].
    @Query("""
        select r from Reservation r
        where r.checkedOutAt is not null
          and r.checkedOutAt < :to
          and (r.returnedAt is null or r.returnedAt > :from)
        """)
    List<Reservation> findCheckedOutOverlapping(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

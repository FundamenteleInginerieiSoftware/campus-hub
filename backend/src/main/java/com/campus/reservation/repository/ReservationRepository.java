package com.campus.reservation.repository;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
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
}

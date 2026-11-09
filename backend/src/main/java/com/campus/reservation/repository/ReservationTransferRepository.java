package com.campus.reservation.repository;

import com.campus.reservation.domain.ReservationTransfer;
import com.campus.reservation.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationTransferRepository extends JpaRepository<ReservationTransfer, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ReservationTransfer t WHERE t.id = :id")
    Optional<ReservationTransfer> findByIdForUpdate(@Param("id") Long id);

    List<ReservationTransfer> findByToUserIdAndStatusOrderByRequestedAtDesc(Long toUserId, TransferStatus status);

    List<ReservationTransfer> findByReservationIdOrderByRequestedAtDesc(Long reservationId);

    Optional<ReservationTransfer> findByReservationIdAndStatus(Long reservationId, TransferStatus status);

    long countByReservationIdAndRequestedAtAfter(Long reservationId, java.time.LocalDateTime threshold);
}

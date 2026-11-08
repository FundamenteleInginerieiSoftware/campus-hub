package com.campus.reservation.domain;

import com.campus.common.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Encapsulates the transfer workflow and state transitions of a reservation between two users.
 */
@Entity
@Table(name = "reservation_transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private Long toUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransferStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public ReservationTransfer(Long reservationId, Long fromUserId, Long toUserId, LocalDateTime requestedAt) {
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.fromUserId = Objects.requireNonNull(fromUserId, "fromUserId must not be null");
        this.toUserId = Objects.requireNonNull(toUserId, "toUserId must not be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.status = TransferStatus.PENDING;
    }

    public void accept(LocalDateTime now) {
        requirePending();
        this.status = TransferStatus.ACCEPTED;
        this.respondedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void reject(LocalDateTime now) {
        requirePending();
        this.status = TransferStatus.REJECTED;
        this.respondedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void cancel(LocalDateTime now) {
        requirePending();
        this.status = TransferStatus.CANCELLED;
        this.respondedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private void requirePending() {
        if (this.status != TransferStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "INVALID_TRANSFER_STATUS_TRANSITION",
                    "Cannot transition transfer in status " + this.status + "; expected PENDING");
        }
    }
}

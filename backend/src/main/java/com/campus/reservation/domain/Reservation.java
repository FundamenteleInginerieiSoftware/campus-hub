package com.campus.reservation.domain;

import com.campus.common.error.InvalidStateTransitionException;
import com.campus.reservation.api.ReservationStatus;
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
 * A reservation representing a student's booking of an asset for a specific time window.
 *
 * <p>State transitions are strictly encapsulated within this entity.
 */
@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "original_asset_id")
    private Long originalAssetId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "checked_out_at")
    private LocalDateTime checkedOutAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Reservation(
            Long userId, Long assetId, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.assetId = Objects.requireNonNull(assetId, "assetId must not be null");
        this.startTime = Objects.requireNonNull(startTime, "startTime must not be null");
        this.endTime = Objects.requireNonNull(endTime, "endTime must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = ReservationStatus.CONFIRMED;
    }

    public void checkOut(LocalDateTime now) {
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.CHECKED_OUT;
        this.checkedOutAt = now;
    }

    public void returnItem(LocalDateTime now) {
        if (this.status != ReservationStatus.CHECKED_OUT && this.status != ReservationStatus.OVERDUE) {
            throw new InvalidStateTransitionException(
                    "Cannot return reservation in status " + this.status + "; expected CHECKED_OUT or OVERDUE");
        }
        this.status = ReservationStatus.RETURNED;
        this.returnedAt = now;
    }

    public void cancel(LocalDateTime now, String reason) {
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }

    public void forceCancel(LocalDateTime now, String reason) {
        if (this.status == ReservationStatus.FORCE_CANCELLED) {
            return;
        }
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.FORCE_CANCELLED;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }

    public void moveTo(Long newAssetId) {
        requireStatus(ReservationStatus.CONFIRMED);
        if (this.originalAssetId == null) {
            this.originalAssetId = this.assetId;
        }
        this.assetId = newAssetId;
    }

    public void markOverdue() {
        requireStatus(ReservationStatus.CHECKED_OUT);
        this.status = ReservationStatus.OVERDUE;
    }

    private void requireStatus(ReservationStatus expected) {
        if (this.status != expected) {
            throw new InvalidStateTransitionException(
                    "Cannot perform action on reservation in status " + this.status + "; expected " + expected);
        }
    }

    // Package-private helper for unit tests to verify behavior from arbitrary states
    void setStatusForTesting(ReservationStatus status) {
        this.status = status;
    }
}

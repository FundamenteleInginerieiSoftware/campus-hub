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

    @Column(name = "bundle_id")
    private java.util.UUID bundleId;

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
        this.checkedOutAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void returnItem(LocalDateTime now) {
        if (this.status != ReservationStatus.CHECKED_OUT && this.status != ReservationStatus.OVERDUE) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Cannot return reservation in status " + this.status + "; expected CHECKED_OUT or OVERDUE");
        }
        this.status = ReservationStatus.RETURNED;
        this.returnedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void cancel(LocalDateTime now, String reason) {
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = Objects.requireNonNull(now, "now must not be null");
        this.cancelReason = reason;
    }

    public void forceCancel(LocalDateTime now, String reason) {
        if (this.status == ReservationStatus.FORCE_CANCELLED) {
            return;
        }
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.FORCE_CANCELLED;
        this.cancelledAt = Objects.requireNonNull(now, "now must not be null");
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

    public void markNoShow() {
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.NO_SHOW;
    }

    public void assignBundle(java.util.UUID bundleId) {
        this.bundleId = bundleId;
    }

    public void extendEndTime(LocalDateTime newEndTime) {
        if (this.status != ReservationStatus.CONFIRMED && this.status != ReservationStatus.CHECKED_OUT) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Cannot extend reservation in status " + this.status + "; expected CONFIRMED or CHECKED_OUT");
        }
        if (!newEndTime.isAfter(this.endTime)) {
            throw new IllegalArgumentException("newEndTime must be after current endTime");
        }
        this.endTime = Objects.requireNonNull(newEndTime, "newEndTime must not be null");
    }

    public void transferOwnership(Long newUserId) {
        requireStatus(ReservationStatus.CONFIRMED);
        this.userId = Objects.requireNonNull(newUserId, "newUserId must not be null");
        // Clear bundle association on transfer to isolate item from original multi-asset kit (#228 point 1)
        this.bundleId = null;
    }

    private void requireStatus(ReservationStatus expected) {
        if (this.status != expected) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Cannot perform action on reservation in status " + this.status + "; expected " + expected);
        }
    }
}

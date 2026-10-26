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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Represents a queued request in the FIFO waitlist for a currently booked equipment time slot.
 */
@Entity
@Table(name = "waitlist_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaitlistStatus status;

    @Column(name = "promoted_reservation_id")
    private Long promotedReservationId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public WaitlistEntry(
            Long userId, Long assetId, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime createdAt) {
        this.userId = userId;
        this.assetId = assetId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = WaitlistStatus.WAITING;
        this.createdAt = createdAt;
    }

    public void markPromoted(Long reservationId, LocalDateTime now) {
        requireWaiting("promote");
        this.status = WaitlistStatus.PROMOTED;
        this.promotedReservationId = reservationId;
        this.resolvedAt = now;
    }

    public void cancel(LocalDateTime now) {
        requireWaiting("cancel");
        this.status = WaitlistStatus.CANCELLED;
        this.resolvedAt = now;
    }

    public void markSkippedQuotaFull(LocalDateTime now) {
        requireWaiting("skip");
        this.status = WaitlistStatus.SKIPPED_QUOTA_FULL;
        this.resolvedAt = now;
    }

    public void markExpired(LocalDateTime now) {
        requireWaiting("expire");
        this.status = WaitlistStatus.EXPIRED;
        this.resolvedAt = now;
    }

    private void requireWaiting(String action) {
        if (this.status != WaitlistStatus.WAITING) {
            throw new InvalidStateTransitionException(
                    "INVALID_WAITLIST_TRANSITION", "Cannot " + action + " waitlist entry in status " + this.status);
        }
    }
}

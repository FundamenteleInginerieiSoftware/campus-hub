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
 * FIFO waitlist entry queued by a student when a desired equipment slot is occupied (#59).
 */
@Entity
@Table(name = "waitlist_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WaitlistStatus status;

    @Column(name = "promoted_reservation_id")
    private Long promotedReservationId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public WaitlistEntry(Long assetId, Long userId, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime now) {
        this.assetId = assetId;
        this.userId = userId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = WaitlistStatus.WAITING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markPromoted(Long reservationId, LocalDateTime now) {
        ensureWaiting();
        this.status = WaitlistStatus.PROMOTED;
        this.promotedReservationId = reservationId;
        this.updatedAt = now;
    }

    public void cancel(LocalDateTime now) {
        ensureWaiting();
        this.status = WaitlistStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void markExpired(LocalDateTime now) {
        ensureWaiting();
        this.status = WaitlistStatus.EXPIRED;
        this.updatedAt = now;
    }

    public void markSkippedQuotaFull(LocalDateTime now) {
        ensureWaiting();
        this.status = WaitlistStatus.SKIPPED_QUOTA_FULL;
        this.updatedAt = now;
    }

    private void ensureWaiting() {
        if (this.status != WaitlistStatus.WAITING) {
            throw new InvalidStateTransitionException(
                    "INVALID_WAITLIST_STATUS_TRANSITION", "Waitlist entry must be WAITING, but was " + this.status);
        }
    }
}

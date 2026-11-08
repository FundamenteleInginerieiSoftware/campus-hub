package com.campus.reservation.domain;

import com.campus.common.error.ConflictException;
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
import lombok.Setter;

/**
 * Represents a temporary hold on a lab equipment time slot.
 *
 * <p>Prevents race conditions while a student confirms details. Automatically expires if
 * confirmation is not received within the TTL window (1 minute).
 */
@Entity
@Table(name = "reservation_holds")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationHold {

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

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private HoldStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Version
    private Long version;

    public ReservationHold(
            Long assetId,
            Long userId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            LocalDateTime expiresAt,
            LocalDateTime createdAt) {
        this.assetId = assetId;
        this.userId = userId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.expiresAt = expiresAt;
        this.status = HoldStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    /**
     * Atomically transitions the hold to {@code CONFIRMED} upon successful reservation creation.
     */
    public void confirm(LocalDateTime now) {
        if (this.status != HoldStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "INVALID_HOLD_STATUS_TRANSITION", "Cannot confirm hold in status " + this.status);
        }
        if (now.isAfter(this.expiresAt)) {
            this.status = HoldStatus.EXPIRED;
            throw new ConflictException("HOLD_EXPIRED", "The reservation hold has expired");
        }
        this.status = HoldStatus.CONFIRMED;
    }

    /**
     * Releases the hold early when the student cancels or navigates away.
     */
    public void release(LocalDateTime now) {
        if (this.status != HoldStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "INVALID_HOLD_STATUS_TRANSITION", "Cannot release hold in status " + this.status);
        }
        this.status = HoldStatus.RELEASED;
    }

    /**
     * Marks the hold as expired if its deadline has elapsed.
     */
    public boolean expire(LocalDateTime now) {
        if (this.status == HoldStatus.ACTIVE && !now.isBefore(this.expiresAt)) {
            this.status = HoldStatus.EXPIRED;
            return true;
        }
        return false;
    }
}

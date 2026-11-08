package com.campus.reservation.web;

import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.ReservationHold;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.LocalDateTime;

public record ReservationHoldResponse(
        @Schema(description = "Hold ID", example = "1") Long id,

        @Schema(description = "Asset ID", example = "10") Long assetId,

        @Schema(description = "User ID who holds the slot", example = "1")
        Long userId,

        @Schema(description = "Start time in campus local time", example = "2026-11-10T14:00:00")
        LocalDateTime startTime,

        @Schema(description = "End time in campus local time", example = "2026-11-10T16:00:00")
        LocalDateTime endTime,

        @Schema(description = "Expiration time in campus local time", example = "2026-11-10T10:05:00")
        LocalDateTime expiresAt,

        @Schema(description = "Hold status", example = "ACTIVE")
        HoldStatus status,

        @Schema(description = "Creation timestamp", example = "2026-11-10T10:00:00")
        LocalDateTime createdAt,

        @Schema(description = "Remaining seconds before hold expiration", example = "295")
        long remainingSeconds) {

    public static ReservationHoldResponse from(ReservationHold hold, LocalDateTime now) {
        long remaining = Math.max(0, Duration.between(now, hold.getExpiresAt()).getSeconds());
        return new ReservationHoldResponse(
                hold.getId(),
                hold.getAssetId(),
                hold.getUserId(),
                hold.getStartTime(),
                hold.getEndTime(),
                hold.getExpiresAt(),
                hold.getStatus(),
                hold.getCreatedAt(),
                remaining);
    }
}

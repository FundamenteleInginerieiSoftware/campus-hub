package com.campus.reservation.web;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REST response representing a reservation.
 */
public record ReservationResponse(
        Long id,
        Long userId,
        Long assetId,
        Long originalAssetId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        ReservationStatus status,
        LocalDateTime createdAt,
        LocalDateTime checkedOutAt,
        LocalDateTime returnedAt,
        LocalDateTime cancelledAt,
        String cancelReason,
        UUID bundleId) {

    public ReservationResponse(
            Long id,
            Long userId,
            Long assetId,
            Long originalAssetId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            ReservationStatus status,
            LocalDateTime createdAt,
            LocalDateTime checkedOutAt,
            LocalDateTime returnedAt,
            LocalDateTime cancelledAt,
            String cancelReason) {
        this(
                id,
                userId,
                assetId,
                originalAssetId,
                startTime,
                endTime,
                status,
                createdAt,
                checkedOutAt,
                returnedAt,
                cancelledAt,
                cancelReason,
                null);
    }

    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getUserId(),
                r.getAssetId(),
                r.getOriginalAssetId(),
                r.getStartTime(),
                r.getEndTime(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getCheckedOutAt(),
                r.getReturnedAt(),
                r.getCancelledAt(),
                r.getCancelReason(),
                r.getBundleId());
    }
}

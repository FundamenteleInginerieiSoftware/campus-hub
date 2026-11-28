package com.campus.reservation.web;

import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import java.time.LocalDateTime;

public record WaitlistEntryResponse(
        Long id,
        Long assetId,
        Long userId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        WaitlistStatus status,
        Long promotedReservationId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static WaitlistEntryResponse from(WaitlistEntry entry) {
        return new WaitlistEntryResponse(
                entry.getId(),
                entry.getAssetId(),
                entry.getUserId(),
                entry.getStartTime(),
                entry.getEndTime(),
                entry.getStatus(),
                entry.getPromotedReservationId(),
                entry.getCreatedAt(),
                entry.getUpdatedAt());
    }
}

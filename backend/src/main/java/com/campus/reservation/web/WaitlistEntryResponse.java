package com.campus.reservation.web;

import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import java.time.LocalDateTime;

/**
 * DTO response representing a student's waitlist entry and its promotion status.
 */
public record WaitlistEntryResponse(
        Long id,
        Long userId,
        Long assetId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        WaitlistStatus status,
        Long promotedReservationId,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt) {

    public static WaitlistEntryResponse from(WaitlistEntry entry) {
        return new WaitlistEntryResponse(
                entry.getId(),
                entry.getUserId(),
                entry.getAssetId(),
                entry.getStartTime(),
                entry.getEndTime(),
                entry.getStatus(),
                entry.getPromotedReservationId(),
                entry.getCreatedAt(),
                entry.getResolvedAt());
    }
}

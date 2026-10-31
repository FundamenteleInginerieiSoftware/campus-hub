package com.campus.reservation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO describing the booking availability of equipment for a requested time slot.
 *
 * @param assetId target asset ID (nullable if querying by category)
 * @param categoryId target category ID (nullable if querying by asset)
 * @param startTime start of the evaluated time slot
 * @param endTime end of the evaluated time slot
 * @param available {@code true} if at least one asset (or the specific asset) is available for booking
 * @param availableAssetIds list of asset IDs that are free and operational during the slot
 * @param conflictReason explanation if the specific asset is unavailable (e.g. {@code "RESERVATION_CONFLICT"}, {@code "ASSET_NOT_BOOKABLE"})
 */
@Schema(description = "Availability evaluation for a time window")
public record AvailabilityResponse(
        @Schema(description = "Target asset ID", example = "10")
        Long assetId,

        @Schema(description = "Target category ID", example = "5")
        Long categoryId,

        @Schema(description = "Slot start time", example = "2026-11-03T14:00:00")
        LocalDateTime startTime,

        @Schema(description = "Slot end time", example = "2026-11-03T16:00:00")
        LocalDateTime endTime,

        @Schema(description = "Whether the requested asset/category can be booked", example = "true")
        boolean available,

        @Schema(description = "IDs of free and bookable assets in this window")
        List<Long> availableAssetIds,

        @Schema(description = "Machine-readable conflict reason if unavailable", example = "RESERVATION_CONFLICT")
        String conflictReason) {
    public static AvailabilityResponse forAsset(
            Long assetId, LocalDateTime start, LocalDateTime end, boolean available, String conflictReason) {
        return new AvailabilityResponse(
                assetId, null, start, end, available, available ? List.of(assetId) : List.of(), conflictReason);
    }

    public static AvailabilityResponse forCategory(
            Long categoryId, LocalDateTime start, LocalDateTime end, List<Long> availableAssetIds) {
        return new AvailabilityResponse(
                null,
                categoryId,
                start,
                end,
                !availableAssetIds.isEmpty(),
                availableAssetIds,
                availableAssetIds.isEmpty() ? "NO_ASSETS_AVAILABLE" : null);
    }
}

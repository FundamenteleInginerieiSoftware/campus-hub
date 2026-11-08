package com.campus.reservation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response payload representing a simultaneous multi-asset reservation group.
 */
@Schema(description = "Details of a multi-asset reservation group")
public record MultiAssetReservationResponse(
        @Schema(
                description = "Unique identifier of the multi-asset bundle",
                example = "b1c2d3e4-f5a6-7890-abcd-ef1234567890")
        UUID bundleId,

        @Schema(description = "Common start time of the reservation bundle", example = "2026-11-09T14:00:00")
        LocalDateTime startTime,

        @Schema(description = "Common end time of the reservation bundle", example = "2026-11-09T16:00:00")
        LocalDateTime endTime,

        @Schema(description = "Total number of equipment items in the bundle", example = "3")
        int totalAssets,

        @Schema(description = "List of individual asset reservations in the bundle")
        List<ReservationResponse> reservations) {}

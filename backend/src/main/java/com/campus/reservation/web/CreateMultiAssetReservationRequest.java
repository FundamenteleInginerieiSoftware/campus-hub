package com.campus.reservation.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Request payload to reserve multiple pieces of equipment simultaneously in a single atomic transaction.
 */
@Schema(description = "Request to create simultaneous multi-asset reservations (bundle)")
public record CreateMultiAssetReservationRequest(
        @NotEmpty(message = "assetIds must not be empty")
        @Size(min = 2, max = 5, message = "assetIds must contain between 2 and 5 assets")
        @Schema(description = "Set of asset IDs to reserve together", example = "[1, 2, 3]")
        Set<@NotNull(message = "assetId must not be null") Long> assetIds,

        @NotNull(message = "startTime must not be null")
        @Schema(description = "Start date-time of the reservation window", example = "2026-11-09T14:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime startTime,

        @NotNull(message = "endTime must not be null")
        @Schema(description = "End date-time of the reservation window", example = "2026-11-09T16:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime endTime) {}

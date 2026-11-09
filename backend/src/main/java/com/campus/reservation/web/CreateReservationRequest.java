package com.campus.reservation.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Payload to create a reservation.
 *
 * <p>SECURITY RULE: The authenticated user ID is never accepted from the request body;
 * it is always extracted from the authenticated user context (CurrentUser).
 */
public record CreateReservationRequest(
        @Schema(description = "ID of the asset to reserve", example = "1") @NotNull
        Long assetId,

        @Schema(
                description = "Booking slot start time (must be aligned to :00 or :30, future weekday 09:00-20:00)",
                example = "2026-11-10T14:00:00")
        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        LocalDateTime startTime,

        @Schema(
                description = "Booking slot end time (must be aligned to :00 or :30, max 4 hours duration)",
                example = "2026-11-10T16:00:00")
        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        LocalDateTime endTime) {}

package com.campus.reservation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Request payload to extend an active reservation's end time.
 */
@Schema(description = "Request to extend an active reservation")
public record ExtendReservationRequest(
        @NotNull(message = "newEndTime must not be null")
        @Schema(description = "Requested new end time for the reservation", example = "2026-11-09T17:00:00")
        LocalDateTime newEndTime) {}

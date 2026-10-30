package com.campus.reservation.web;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Payload to create a reservation.
 *
 * <p>SECURITY RULE: The authenticated user ID is never accepted from the request body;
 * it is always extracted from the authenticated user context (CurrentUser).
 */
public record CreateReservationRequest(
        @NotNull Long assetId,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {}

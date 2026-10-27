package com.campus.reservation.api;

import java.time.LocalDateTime;

/**
 * Public summary projection of a reservation exposed across modules.
 */
public record ReservationSummary(
        Long id, Long userId, Long assetId, LocalDateTime start, LocalDateTime end, ReservationStatus status) {}

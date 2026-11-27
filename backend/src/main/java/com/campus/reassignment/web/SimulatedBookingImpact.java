package com.campus.reassignment.web;

import com.campus.reassignment.domain.ReassignmentOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record SimulatedBookingImpact(
        @Schema(description = "ID of the affected upcoming CONFIRMED reservation", example = "10")
        Long reservationId,

        @Schema(description = "ID of the student who owns the reservation", example = "2")
        Long userId,

        @Schema(description = "Booking start time in campus local time", example = "2026-11-10T10:00:00")
        LocalDateTime startTime,

        @Schema(description = "Booking end time in campus local time", example = "2026-11-10T12:00:00")
        LocalDateTime endTime,

        @Schema(description = "Predicted reassignment outcome", example = "REASSIGNED")
        ReassignmentOutcome outcome,

        @Schema(description = "Predicted substitute asset ID, or null if force-cancelled", example = "2")
        Long substituteAssetId,

        @Schema(
                description = "Predicted substitute asset name, or null if force-cancelled",
                example = "Rigol DS1054Z #2")
        String substituteAssetName,

        @Schema(
                description = "Strategy that selected the substitute, or null if force-cancelled",
                example = "SameModelStrategy")
        String strategy) {}

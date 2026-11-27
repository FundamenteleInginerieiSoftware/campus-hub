package com.campus.reassignment.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record MaintenanceImpactResponse(
        @Schema(description = "ID of the simulated target asset", example = "1")
        Long assetId,

        @Schema(description = "Name of the simulated target asset", example = "Rigol DS1054Z #1")
        String assetName,

        @Schema(description = "Maintenance window start in campus local time", example = "2026-11-10T09:00:00")
        LocalDateTime windowStart,

        @Schema(description = "Maintenance window end in campus local time", example = "2026-11-10T17:00:00")
        LocalDateTime windowEnd,

        @Schema(description = "Total future CONFIRMED bookings overlapping the maintenance window", example = "2")
        int totalAffected,

        @Schema(description = "Number of bookings predicted to move to a substitute", example = "1")
        int reassignedCount,

        @Schema(description = "Number of bookings predicted to be force-cancelled", example = "1")
        int forceCancelledCount,

        @Schema(description = "Weighted impact score (1 per reassigned + 3 per force-cancelled)", example = "4")
        int disruptionScore,

        @Schema(description = "Per-booking simulated outcome breakdown ordered by startTime, then id")
        List<SimulatedBookingImpact> bookings) {}

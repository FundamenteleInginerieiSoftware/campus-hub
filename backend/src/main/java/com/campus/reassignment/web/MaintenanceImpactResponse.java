package com.campus.reassignment.web;

import java.time.LocalDateTime;
import java.util.List;

public record MaintenanceImpactResponse(
        Long assetId,
        String assetName,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        int totalAffected,
        int reassignedCount,
        int forceCancelledCount,
        int disruptionScore,
        List<SimulatedBookingImpact> bookings) {}

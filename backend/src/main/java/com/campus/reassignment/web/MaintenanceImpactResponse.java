package com.campus.reassignment.web;

import com.campus.reassignment.domain.ReassignmentOutcome;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Dry-run simulation report showing how upcoming reservations would be reassigned if an asset enters
 * maintenance during the requested window.
 */
public record MaintenanceImpactResponse(
        Long assetId,
        String assetName,
        LocalDateTime requestedStart,
        LocalDateTime requestedEnd,
        int totalAffectedReservations,
        int predictedReassignedCount,
        int unresolvableForceCancelCount,
        List<SimulatedReassignmentItem> simulatedItems) {

    public record SimulatedReassignmentItem(
            Long reservationId,
            Long userId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            ReassignmentOutcome predictedOutcome,
            Long suggestedSubstituteAssetId,
            String suggestedSubstituteName,
            String strategyUsed) {}
}

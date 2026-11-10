package com.campus.reassignment.service.optimal;

import java.time.LocalDateTime;

/**
 * Individual action item within a global optimal reassignment plan.
 */
public record ReassignmentActionItem(
        Long reservationId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long originalAssetId,
        Long assignedAssetId,
        String assignedAssetName,
        String strategyName,
        int penaltyScore) {}

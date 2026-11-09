package com.campus.reassignment.web;

import java.util.List;

/**
 * Full response containing the global optimal reassignment solution, action breakdowns,
 * and academic comparison against the greedy strategy.
 */
public record OptimalReassignmentPlanResponse(
        Long damagedAssetId,
        String damagedAssetName,
        int totalReservations,
        int rescuedReservations,
        int forceCancelledReservations,
        int sameModelCount,
        int sameCategoryCount,
        boolean executed,
        List<ReassignmentActionItem> actions,
        GreedyComparisonSummary comparison) {}

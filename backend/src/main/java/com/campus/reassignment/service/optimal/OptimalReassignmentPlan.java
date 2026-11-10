package com.campus.reassignment.service.optimal;

import java.util.List;

/**
 * Plan computed by {@link OptimalReassignmentSolver}, plus the counts actually executed on a live run.
 * The controller maps it to {@code OptimalReassignmentPlanResponse}.
 */
public record OptimalReassignmentPlan(
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

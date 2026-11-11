package com.campus.reassignment.web;

import com.campus.reassignment.service.optimal.GreedyComparisonSummary;
import com.campus.reassignment.service.optimal.OptimalReassignmentPlan;
import com.campus.reassignment.service.optimal.ReassignmentActionItem;
import java.util.List;

/**
 * Response of {@code POST /api/v1/admin/reassignments/solve-optimal}. Kept in {@code web/} so the
 * service layer does not depend on the HTTP shape.
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
        GreedyComparisonSummary comparison) {

    static OptimalReassignmentPlanResponse from(OptimalReassignmentPlan plan) {
        return new OptimalReassignmentPlanResponse(
                plan.damagedAssetId(),
                plan.damagedAssetName(),
                plan.totalReservations(),
                plan.rescuedReservations(),
                plan.forceCancelledReservations(),
                plan.sameModelCount(),
                plan.sameCategoryCount(),
                plan.executed(),
                plan.actions(),
                plan.comparison());
    }
}

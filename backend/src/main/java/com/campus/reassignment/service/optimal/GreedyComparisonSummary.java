package com.campus.reassignment.service.optimal;

/**
 * Branch-and-bound result next to the chronological greedy result for the same input.
 */
public record GreedyComparisonSummary(
        int optimalRescuedCount,
        int greedyRescuedCount,
        int optimalForceCancelledCount,
        int greedyForceCancelledCount,
        int optimalTotalCost,
        int greedyTotalCost,
        boolean optimalStrictlyBetter,
        String explanation) {}

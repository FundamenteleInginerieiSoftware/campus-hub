package com.campus.reassignment.web;

/**
 * Comparative metrics demonstrating how Min-Cost Max-Flow outperforms a chronological greedy heuristic.
 */
public record GreedyComparisonSummary(
        int mcmfRescuedCount,
        int greedyRescuedCount,
        int mcmfForceCancelledCount,
        int greedyForceCancelledCount,
        int mcmfTotalCost,
        int greedyTotalCost,
        boolean optimalStrictlyBetter,
        String explanation) {}

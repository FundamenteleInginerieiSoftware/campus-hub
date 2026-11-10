package com.campus.reassignment.service.optimal;

import com.campus.catalog.api.AssetSummary;
import com.campus.reassignment.web.GreedyComparisonSummary;
import com.campus.reassignment.web.OptimalReassignmentPlanResponse;
import com.campus.reassignment.web.ReassignmentActionItem;
import com.campus.reservation.api.ReservationSummary;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.springframework.stereotype.Component;

/**
 * Global optimal reassignment solver employing Min-Cost Max-Flow (MCMF) over a bipartite
 * interval-scheduling network.
 *
 * <p>Unlike the chronological greedy strategy (which can greedily allocate a versatile unit
 * and force-cancel later reservations), this solver finds the global configuration that:
 * <ol>
 *   <li>Maximizes the total count of rescued student reservations (Max-Flow).</li>
 *   <li>Minimizes total substitution degradation / student inconvenience (Min-Cost).</li>
 * </ol>
 */
@Component
public class OptimalReassignmentSolver {

    public static final int COST_SAME_MODEL = 10;
    public static final int COST_SAME_CATEGORY = 50;
    public static final int COST_FORCE_CANCEL = 1000;

    public record PlannedAction(
            ReservationSummary booking, AssetSummary assignedAsset, String strategyName, int penaltyCost) {}

    public record Solution(
            List<PlannedAction> actions,
            int totalCost,
            int rescuedCount,
            int cancelledCount,
            int sameModelCount,
            int sameCategoryCount) {}

    /**
     * Solves the optimal reassignment problem globally and returns the full plan response
     * including side-by-side comparison with the chronological greedy heuristic.
     */
    public OptimalReassignmentPlanResponse solve(
            Long damagedAssetId,
            String damagedAssetName,
            List<ReservationSummary> bookings,
            AssetSummary damagedAsset,
            List<AssetSummary> candidateSubstitutes,
            BiPredicate<Long, ReservationSummary> isAssetFreeChecker) {

        if (bookings == null || bookings.isEmpty()) {
            return new OptimalReassignmentPlanResponse(
                    damagedAssetId,
                    damagedAssetName,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    List.of(),
                    new GreedyComparisonSummary(0, 0, 0, 0, 0, 0, false, "No upcoming reservations affected."));
        }

        // 1. Solve via Min-Cost Max-Flow
        Solution mcmfSolution = solveMcmf(bookings, damagedAsset, candidateSubstitutes, isAssetFreeChecker);

        // 2. Solve via Chronological Greedy for academic comparison
        Solution greedySolution = solveGreedy(bookings, damagedAsset, candidateSubstitutes, isAssetFreeChecker);

        boolean isStrictlyBetter = (mcmfSolution.rescuedCount() > greedySolution.rescuedCount())
                || (mcmfSolution.rescuedCount() == greedySolution.rescuedCount()
                        && mcmfSolution.totalCost() < greedySolution.totalCost());

        String explanation = String.format(
                "MCMF rescued %d/%d reservations (cost %d) vs Greedy %d/%d (cost %d). Global optimization %s.",
                mcmfSolution.rescuedCount(),
                bookings.size(),
                mcmfSolution.totalCost(),
                greedySolution.rescuedCount(),
                bookings.size(),
                greedySolution.totalCost(),
                isStrictlyBetter
                        ? "strictly saved more reservations or reduced model degradation"
                        : "matched optimal baseline");

        List<ReassignmentActionItem> actionItems = mcmfSolution.actions().stream()
                .map(action -> new ReassignmentActionItem(
                        action.booking().id(),
                        action.booking().start(),
                        action.booking().end(),
                        damagedAssetId,
                        action.assignedAsset() != null ? action.assignedAsset().id() : null,
                        action.assignedAsset() != null ? action.assignedAsset().name() : null,
                        action.strategyName(),
                        action.penaltyCost()))
                .toList();

        return new OptimalReassignmentPlanResponse(
                damagedAssetId,
                damagedAssetName,
                bookings.size(),
                mcmfSolution.rescuedCount(),
                mcmfSolution.cancelledCount(),
                mcmfSolution.sameModelCount(),
                mcmfSolution.sameCategoryCount(),
                false,
                actionItems,
                new GreedyComparisonSummary(
                        mcmfSolution.rescuedCount(),
                        greedySolution.rescuedCount(),
                        mcmfSolution.cancelledCount(),
                        greedySolution.cancelledCount(),
                        mcmfSolution.totalCost(),
                        greedySolution.totalCost(),
                        isStrictlyBetter,
                        explanation));
    }

    /**
     * Builds and solves the Min-Cost Max-Flow graph using interval conflict tracks.
     */
    public Solution solveMcmf(
            List<ReservationSummary> bookings,
            AssetSummary damagedAsset,
            List<AssetSummary> candidateSubstitutes,
            BiPredicate<Long, ReservationSummary> isAssetFreeChecker) {

        int n = bookings.size();
        if (n == 0) {
            return new Solution(List.of(), 0, 0, 0, 0, 0);
        }

        // Step 1: Decompose bookings into minimal conflict tracks using interval graph coloring.
        // Within each track, bookings are strictly pairwise non-overlapping.
        int[] trackAssignment = computeIntervalTracks(bookings);
        int numTracks = 0;
        for (int t : trackAssignment) {
            if (t + 1 > numTracks) {
                numTracks = t + 1;
            }
        }
        int[] trackSizes = new int[numTracks];
        List<List<ReservationSummary>> trackBookings = new ArrayList<>(numTracks);
        for (int t = 0; t < numTracks; t++) {
            trackBookings.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            int t = trackAssignment[i];
            trackSizes[t]++;
            trackBookings.get(t).add(bookings.get(i));
        }

        int m = candidateSubstitutes.size();

        // Node indexing:
        // 0: Source S
        // 1..numTracks: Track nodes Track_t (for t in 0..numTracks-1)
        // (numTracks + 1)..(numTracks + m): Candidate Asset nodes Asset_j (for j in 0..m-1)
        // Sink T: numTracks + m + 1
        int source = 0;
        int trackBase = 1;
        int candidateBase = numTracks + 1;
        int sink = numTracks + m + 1;
        int totalNodes = sink + 1;

        MinCostMaxFlow mcmf = new MinCostMaxFlow(totalNodes);

        record CandidateTrackEdge(
                MinCostMaxFlow.Edge edge, AssetSummary candidate, int trackId, String strategy, int unitCost) {}
        record FallbackTrackEdge(MinCostMaxFlow.Edge edge, int trackId) {}

        List<CandidateTrackEdge> candidateTrackEdges = new ArrayList<>();
        List<FallbackTrackEdge> fallbackTrackEdges = new ArrayList<>();

        // 1. Source -> Track nodes (capacity 1 per track, cost 0)
        for (int t = 0; t < numTracks; t++) {
            mcmf.addEdge(source, trackBase + t, 1, 0);

            // Fallback: Track_t -> Sink with cost = trackSizes[t] * COST_FORCE_CANCEL
            MinCostMaxFlow.Edge fb = mcmf.addEdge(trackBase + t, sink, 1, trackSizes[t] * COST_FORCE_CANCEL);
            fallbackTrackEdges.add(new FallbackTrackEdge(fb, t));
        }

        // 2. Candidate Asset nodes -> Sink (capacity 1 per candidate, cost 0)
        // This strictly guarantees that each candidate asset is assigned to AT MOST ONE track,
        // preventing cross-track double-booking across overlapping intervals.
        for (int j = 0; j < m; j++) {
            mcmf.addEdge(candidateBase + j, sink, 1, 0);
        }

        // 3. Connect Track nodes -> Compatible Candidate Asset nodes
        for (int j = 0; j < m; j++) {
            AssetSummary candidate = candidateSubstitutes.get(j);

            boolean isSameModel = candidate.model().equals(damagedAsset.model());
            boolean isSameCategory = candidate.categoryId().equals(damagedAsset.categoryId());

            if (!isSameModel && !isSameCategory) {
                continue;
            }

            int unitCost = isSameModel ? COST_SAME_MODEL : COST_SAME_CATEGORY;
            String strategy = isSameModel ? "SameModel" : "SameCategory";

            for (int t = 0; t < numTracks; t++) {
                List<ReservationSummary> bList = trackBookings.get(t);
                int compatibleCount = 0;
                int incompatibleCount = 0;

                for (ReservationSummary b : bList) {
                    if (isAssetFreeChecker.test(candidate.id(), b)) {
                        compatibleCount++;
                    } else {
                        incompatibleCount++;
                    }
                }

                if (compatibleCount > 0) {
                    int cost = (compatibleCount * unitCost) + (incompatibleCount * COST_FORCE_CANCEL);
                    MinCostMaxFlow.Edge edge = mcmf.addEdge(trackBase + t, candidateBase + j, 1, cost);
                    candidateTrackEdges.add(new CandidateTrackEdge(edge, candidate, t, strategy, unitCost));
                }
            }
        }

        // Solve for targetFlow = numTracks (each track receives flow from a candidate or fallback)
        MinCostMaxFlow.Result result = mcmf.solve(source, sink, numTracks);

        // Decode matched actions
        List<PlannedAction> actions = new ArrayList<>();
        Set<Integer> matchedTracks = new HashSet<>();
        int sameModelCount = 0;
        int sameCategoryCount = 0;
        int rescuedCount = 0;
        int cancelledCount = 0;

        for (CandidateTrackEdge cte : candidateTrackEdges) {
            if (cte.edge.flow > 0) {
                matchedTracks.add(cte.trackId);
                List<ReservationSummary> bList = trackBookings.get(cte.trackId);
                for (ReservationSummary b : bList) {
                    if (isAssetFreeChecker.test(cte.candidate.id(), b)) {
                        actions.add(new PlannedAction(b, cte.candidate, cte.strategy, cte.unitCost));
                        rescuedCount++;
                        if ("SameModel".equals(cte.strategy)) {
                            sameModelCount++;
                        } else {
                            sameCategoryCount++;
                        }
                    } else {
                        actions.add(new PlannedAction(b, null, "ForceCancel", COST_FORCE_CANCEL));
                        cancelledCount++;
                    }
                }
            }
        }

        for (FallbackTrackEdge fte : fallbackTrackEdges) {
            if (fte.edge.flow > 0 && !matchedTracks.contains(fte.trackId)) {
                List<ReservationSummary> bList = trackBookings.get(fte.trackId);
                for (ReservationSummary b : bList) {
                    actions.add(new PlannedAction(b, null, "ForceCancel", COST_FORCE_CANCEL));
                    cancelledCount++;
                }
            }
        }

        actions.sort(Comparator.comparing(pa -> pa.booking().start()));

        return new Solution(
                actions, result.totalCost(), rescuedCount, cancelledCount, sameModelCount, sameCategoryCount);
    }

    /**
     * Decomposes the reservation set into minimal conflict tracks using greedy interval coloring.
     * Within each track, reservations are strictly pairwise non-overlapping, enabling a substitute
     * asset assigned to that track to fulfill all non-overlapping reservations in the track.
     */
    private int[] computeIntervalTracks(List<ReservationSummary> bookings) {
        int n = bookings.size();
        if (n == 0) {
            return new int[0];
        }
        int[] trackAssignment = new int[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparing((Integer i) -> bookings.get(i).start()));

        List<LocalDateTime> trackEndTimes = new ArrayList<>();
        for (int idx : order) {
            ReservationSummary b = bookings.get(idx);
            int assignedTrack = -1;
            for (int t = 0; t < trackEndTimes.size(); t++) {
                if (!b.start().isBefore(trackEndTimes.get(t))) {
                    assignedTrack = t;
                    trackEndTimes.set(t, b.end());
                    break;
                }
            }
            if (assignedTrack == -1) {
                assignedTrack = trackEndTimes.size();
                trackEndTimes.add(b.end());
            }
            trackAssignment[idx] = assignedTrack;
        }

        return trackAssignment;
    }

    private static boolean overlaps(ReservationSummary a, ReservationSummary b) {
        return a.start().isBefore(b.end()) && a.end().isAfter(b.start());
    }

    /**
     * Solves the reassignment problem using chronological greedy heuristic.
     */
    public Solution solveGreedy(
            List<ReservationSummary> bookings,
            AssetSummary damagedAsset,
            List<AssetSummary> candidateSubstitutes,
            BiPredicate<Long, ReservationSummary> isAssetFreeChecker) {

        List<ReservationSummary> sorted = bookings.stream()
                .sorted(Comparator.comparing(ReservationSummary::start))
                .toList();

        Map<Long, List<ReservationSummary>> assetAssignedSlots = new HashMap<>();
        List<PlannedAction> actions = new ArrayList<>();
        int totalCost = 0;
        int rescuedCount = 0;
        int cancelledCount = 0;
        int sameModelCount = 0;
        int sameCategoryCount = 0;

        for (ReservationSummary booking : sorted) {
            AssetSummary chosen = null;
            String strategy = null;
            int cost = 0;

            // 1. Try SameModel
            for (AssetSummary candidate : candidateSubstitutes) {
                if (candidate.model().equals(damagedAsset.model())
                        && isLocallyFree(candidate.id(), booking, assetAssignedSlots, isAssetFreeChecker)) {
                    chosen = candidate;
                    strategy = "SameModel";
                    cost = COST_SAME_MODEL;
                    sameModelCount++;
                    break;
                }
            }

            // 2. Try SameCategory
            if (chosen == null) {
                for (AssetSummary candidate : candidateSubstitutes) {
                    if (candidate.categoryId().equals(damagedAsset.categoryId())
                            && isLocallyFree(candidate.id(), booking, assetAssignedSlots, isAssetFreeChecker)) {
                        chosen = candidate;
                        strategy = "SameCategory";
                        cost = COST_SAME_CATEGORY;
                        sameCategoryCount++;
                        break;
                    }
                }
            }

            if (chosen != null) {
                assetAssignedSlots
                        .computeIfAbsent(chosen.id(), k -> new ArrayList<>())
                        .add(booking);
                actions.add(new PlannedAction(booking, chosen, strategy, cost));
                totalCost += cost;
                rescuedCount++;
            } else {
                actions.add(new PlannedAction(booking, null, "ForceCancel", COST_FORCE_CANCEL));
                totalCost += COST_FORCE_CANCEL;
                cancelledCount++;
            }
        }

        return new Solution(actions, totalCost, rescuedCount, cancelledCount, sameModelCount, sameCategoryCount);
    }

    private static boolean isLocallyFree(
            Long assetId,
            ReservationSummary candidateBooking,
            Map<Long, List<ReservationSummary>> alreadyAssigned,
            BiPredicate<Long, ReservationSummary> isAssetFreeChecker) {

        if (!isAssetFreeChecker.test(assetId, candidateBooking)) {
            return false;
        }

        List<ReservationSummary> assigned = alreadyAssigned.getOrDefault(assetId, Collections.emptyList());
        for (ReservationSummary existing : assigned) {
            if (overlaps(existing, candidateBooking)) {
                return false;
            }
        }
        return true;
    }
}

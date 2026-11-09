package com.campus.reassignment.service.optimal;

import com.campus.catalog.api.AssetSummary;
import com.campus.reassignment.web.GreedyComparisonSummary;
import com.campus.reassignment.web.OptimalReassignmentPlanResponse;
import com.campus.reassignment.web.ReassignmentActionItem;
import com.campus.reservation.api.ReservationSummary;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
     * Builds and solves the Min-Cost Max-Flow graph using interval conflict clusters.
     */
    public Solution solveMcmf(
            List<ReservationSummary> bookings,
            AssetSummary damagedAsset,
            List<AssetSummary> candidateSubstitutes,
            BiPredicate<Long, ReservationSummary> isAssetFreeChecker) {

        int n = bookings.size();

        // Step 1: Group bookings into connected overlap conflict clusters.
        // Bookings in separate clusters do not overlap in time and can share the same substitute asset.
        int[] clusterAssignment = computeOverlapClusters(bookings);
        int numClusters = 0;
        for (int c : clusterAssignment) {
            if (c + 1 > numClusters) {
                numClusters = c + 1;
            }
        }

        int m = candidateSubstitutes.size();

        // Node indexing:
        // 0: Source S
        // 1..n: Reservation nodes R_i (for i in 0..n-1)
        // For each candidate j (0..m-1) and cluster c (0..numClusters-1):
        //   Asset-Cluster slot node: n + 1 + j * numClusters + c
        // Sink T: n + 1 + m * numClusters
        int source = 0;
        int reservationBase = 1;
        int assetSlotBase = n + 1;
        int sink = n + 1 + m * numClusters;
        int totalNodes = sink + 1;

        MinCostMaxFlow mcmf = new MinCostMaxFlow(totalNodes);

        record CandidateEdge(
                MinCostMaxFlow.Edge edge,
                ReservationSummary booking,
                AssetSummary candidate,
                String strategy,
                int cost) {}
        record FallbackEdge(MinCostMaxFlow.Edge edge, ReservationSummary booking) {}

        List<CandidateEdge> candidateEdges = new ArrayList<>();
        List<FallbackEdge> fallbackEdges = new ArrayList<>();

        // 1. Source -> Reservation nodes & Fallback edges
        for (int i = 0; i < n; i++) {
            mcmf.addEdge(source, reservationBase + i, 1, 0);

            // Fallback: R_i -> Sink (cap 1, cost 1000 for force cancel)
            MinCostMaxFlow.Edge fb = mcmf.addEdge(reservationBase + i, sink, 1, COST_FORCE_CANCEL);
            fallbackEdges.add(new FallbackEdge(fb, bookings.get(i)));
        }

        // 2. Candidate Asset-Cluster slot nodes -> Sink
        // For each candidate asset and cluster, at most 1 reservation in that cluster can occupy the asset.
        for (int j = 0; j < m; j++) {
            for (int c = 0; c < numClusters; c++) {
                int slotNode = assetSlotBase + j * numClusters + c;
                mcmf.addEdge(slotNode, sink, 1, 0);
            }
        }

        // 3. Connect Compatible Reservations -> Candidate Asset-Cluster slots
        for (int j = 0; j < m; j++) {
            AssetSummary candidate = candidateSubstitutes.get(j);

            boolean isSameModel = candidate.model().equals(damagedAsset.model());
            boolean isSameCategory = candidate.categoryId().equals(damagedAsset.categoryId());

            if (!isSameModel && !isSameCategory) {
                continue;
            }

            int cost = isSameModel ? COST_SAME_MODEL : COST_SAME_CATEGORY;
            String strategy = isSameModel ? "SameModel" : "SameCategory";

            for (int i = 0; i < n; i++) {
                ReservationSummary booking = bookings.get(i);
                if (isAssetFreeChecker.test(candidate.id(), booking)) {
                    int clusterId = clusterAssignment[i];
                    int slotNode = assetSlotBase + j * numClusters + clusterId;

                    MinCostMaxFlow.Edge edge = mcmf.addEdge(reservationBase + i, slotNode, 1, cost);
                    candidateEdges.add(new CandidateEdge(edge, booking, candidate, strategy, cost));
                }
            }
        }

        // Solve for targetFlow = n (every reservation must be matched or force cancelled)
        MinCostMaxFlow.Result result = mcmf.solve(source, sink, n);

        // Decode matched actions
        List<PlannedAction> actions = new ArrayList<>();
        Set<Long> matchedBookingIds = new HashSet<>();
        int sameModelCount = 0;
        int sameCategoryCount = 0;
        int rescuedCount = 0;
        int cancelledCount = 0;

        for (CandidateEdge ce : candidateEdges) {
            if (ce.edge.flow > 0) {
                matchedBookingIds.add(ce.booking.id());
                actions.add(new PlannedAction(ce.booking, ce.candidate, ce.strategy, ce.cost));
                rescuedCount++;
                if ("SameModel".equals(ce.strategy)) {
                    sameModelCount++;
                } else {
                    sameCategoryCount++;
                }
            }
        }

        for (FallbackEdge fe : fallbackEdges) {
            if (fe.edge.flow > 0 && !matchedBookingIds.contains(fe.booking.id())) {
                actions.add(new PlannedAction(fe.booking, null, "ForceCancel", COST_FORCE_CANCEL));
                cancelledCount++;
            }
        }

        actions.sort(Comparator.comparing(pa -> pa.booking().start()));

        return new Solution(
                actions, result.totalCost(), rescuedCount, cancelledCount, sameModelCount, sameCategoryCount);
    }

    /**
     * Groups reservations into connected overlap conflict clusters.
     * Two reservations are in the same cluster if their time windows overlap.
     */
    private int[] computeOverlapClusters(List<ReservationSummary> bookings) {
        int n = bookings.size();
        int[] clusterAssignment = new int[n];
        boolean[] visited = new boolean[n];
        int currentCluster = 0;

        for (int i = 0; i < n; i++) {
            if (visited[i]) {
                continue;
            }

            Queue<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            visited[i] = true;
            clusterAssignment[i] = currentCluster;

            while (!queue.isEmpty()) {
                int curr = queue.poll();
                ReservationSummary b1 = bookings.get(curr);

                for (int next = 0; next < n; next++) {
                    if (!visited[next]) {
                        ReservationSummary b2 = bookings.get(next);
                        if (overlaps(b1, b2)) {
                            visited[next] = true;
                            clusterAssignment[next] = currentCluster;
                            queue.add(next);
                        }
                    }
                }
            }
            currentCluster++;
        }

        return clusterAssignment;
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

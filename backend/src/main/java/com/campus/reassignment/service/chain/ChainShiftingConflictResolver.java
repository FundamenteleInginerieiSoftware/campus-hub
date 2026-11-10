package com.campus.reassignment.service.chain;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves reservation conflicts via Augmenting Path Chain Shifting.
 *
 * <p>When a student desires asset X but X is already booked, this engine finds a valid
 * chain of relocations (e.g. Res A on X -> Y, Res B on Y -> Z where Z is idle) such that
 * every student retains an identical model substitute and asset X is freed without cancellations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainShiftingConflictResolver {

    public static final int MAX_CHAIN_DEPTH = 3;

    private final CatalogApi catalogApi;
    private final ReservationApi reservationApi;
    private final Clock clock;

    public ChainShiftPlan planChainShift(Long targetAssetId, LocalDateTime startTime, LocalDateTime endTime) {
        // 1. If target asset is already free, zero shifts needed
        if (reservationApi.isAssetFree(targetAssetId, startTime, endTime, null)) {
            return new ChainShiftPlan(true, List.of(), "Target equipment is already free during the requested window.");
        }

        AssetSummary targetAsset = catalogApi.getAsset(targetAssetId);

        // 2. Find all active reservations directly blocking the target asset during [startTime, endTime]
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationSummary> upcoming =
                reservationApi.findUpcomingActiveByAsset(targetAssetId, now.isBefore(startTime) ? now : startTime);
        List<ReservationSummary> directBlockers = upcoming.stream()
                .filter(res -> overlaps(res.start(), res.end(), startTime, endTime))
                .toList();

        if (directBlockers.isEmpty()) {
            return new ChainShiftPlan(
                    false,
                    List.of(),
                    String.format(
                            "Target equipment '%s' is not free during the requested window, but has no shiftable confirmed reservations (it may be in use: CHECKED_OUT or OVERDUE).",
                            targetAsset.name()));
        }

        List<ChainShiftStep> combinedExecutionSteps = new ArrayList<>();
        Set<Long> lockedAssetsInChain = new HashSet<>();
        lockedAssetsInChain.add(targetAssetId);

        for (ReservationSummary blocker : directBlockers) {
            List<ChainShiftStep> singleChain = findAugmentingPath(blocker, targetAsset, lockedAssetsInChain);
            if (singleChain == null) {
                return new ChainShiftPlan(
                        false,
                        List.of(),
                        String.format(
                                "Unable to find an augmenting shift chain for blocking reservation #%d on asset '%s'.",
                                blocker.id(), targetAsset.name()));
            }

            // Record all assets utilized in this chain to avoid cycle contamination
            for (ChainShiftStep step : singleChain) {
                lockedAssetsInChain.add(step.toAssetId());
            }

            // Chain steps must be executed from terminal idle asset back to root
            List<ChainShiftStep> executionOrder = new ArrayList<>(singleChain);
            Collections.reverse(executionOrder);
            combinedExecutionSteps.addAll(executionOrder);
        }

        String explanation = String.format(
                "Found feasible augmenting path of %d shift(s) freeing '%s' for [%s - %s].",
                combinedExecutionSteps.size(), targetAsset.name(), startTime, endTime);

        return new ChainShiftPlan(true, combinedExecutionSteps, explanation);
    }

    /**
     * Breadth-First Search for the shortest augmenting path shifting {@code rootRes} off {@code rootAsset}.
     */
    private List<ChainShiftStep> findAugmentingPath(
            ReservationSummary rootRes, AssetSummary rootAsset, Set<Long> globallyLockedAssets) {

        record BfsNode(
                ReservationSummary reservation,
                AssetSummary currentAsset,
                List<ChainShiftStep> steps,
                Set<Long> visitedAssets) {}

        Queue<BfsNode> queue = new ArrayDeque<>();
        Set<Long> initialVisited = new HashSet<>(globallyLockedAssets);
        initialVisited.add(rootAsset.id());
        queue.add(new BfsNode(rootRes, rootAsset, new ArrayList<>(), initialVisited));

        while (!queue.isEmpty()) {
            BfsNode curr = queue.poll();

            if (curr.steps().size() >= MAX_CHAIN_DEPTH) {
                continue;
            }

            // Collect candidate substitutes: exact same model first, then same category
            List<AssetSummary> candidates = new ArrayList<>();
            candidates.addAll(catalogApi.findAvailableByModel(
                    curr.currentAsset().model(), curr.currentAsset().id()));
            for (AssetSummary catSub : catalogApi.findAvailableByCategory(
                    curr.currentAsset().categoryId(), curr.currentAsset().id())) {
                if (candidates.stream().noneMatch(c -> c.id().equals(catSub.id()))) {
                    candidates.add(catSub);
                }
            }

            for (AssetSummary candidate : candidates) {
                if (curr.visitedAssets().contains(candidate.id())) {
                    continue;
                }

                boolean isSameModel =
                        candidate.model().equals(curr.currentAsset().model());
                String strategyName = isSameModel ? "SameModel" : "SameCategory";

                ChainShiftStep proposedStep = new ChainShiftStep(
                        curr.reservation().id(),
                        curr.reservation().userId(),
                        curr.currentAsset().id(),
                        curr.currentAsset().name(),
                        candidate.id(),
                        candidate.name(),
                        curr.reservation().start(),
                        curr.reservation().end(),
                        strategyName);

                // Check if candidate asset is completely idle during the required slot
                if (reservationApi.isAssetFree(
                        candidate.id(),
                        curr.reservation().start(),
                        curr.reservation().end(),
                        null)) {
                    List<ChainShiftStep> finalSteps = new ArrayList<>(curr.steps());
                    finalSteps.add(proposedStep);
                    return finalSteps; // Augmenting path terminated at idle asset
                }

                // If not idle, inspect the blocking reservation on this candidate
                LocalDateTime queryStart = curr.reservation().start().minusDays(1);
                List<ReservationSummary> subBlockers =
                        reservationApi.findUpcomingActiveByAsset(candidate.id(), queryStart).stream()
                                .filter(res -> overlaps(
                                        res.start(),
                                        res.end(),
                                        curr.reservation().start(),
                                        curr.reservation().end()))
                                .toList();

                // If candidate is blocked by exactly 1 reservation, explore shifting that reservation in chain
                if (subBlockers.size() == 1) {
                    ReservationSummary nextRes = subBlockers.get(0);
                    Set<Long> nextVisited = new HashSet<>(curr.visitedAssets());
                    nextVisited.add(candidate.id());

                    List<ChainShiftStep> nextSteps = new ArrayList<>(curr.steps());
                    nextSteps.add(proposedStep);

                    queue.add(new BfsNode(nextRes, candidate, nextSteps, nextVisited));
                }
            }
        }

        return null; // No augmenting path within MAX_CHAIN_DEPTH
    }

    private static boolean overlaps(LocalDateTime s1, LocalDateTime e1, LocalDateTime s2, LocalDateTime e2) {
        return s1.isBefore(e2) && e1.isAfter(s2);
    }
}

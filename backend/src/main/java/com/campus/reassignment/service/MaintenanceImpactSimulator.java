package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.web.MaintenanceImpactResponse;
import com.campus.reassignment.web.MaintenanceImpactResponse.SimulatedReassignmentItem;
import com.campus.reassignment.web.SimulateMaintenanceRequest;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dry-run simulator that predicts reassignment outcomes for planned maintenance without mutating the
 * database, delegating directly to the ordered {@link SubstituteStrategy} chain.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenanceImpactSimulator {

    private final CatalogApi catalogApi;
    private final ReservationApi reservationApi;
    private final List<SubstituteStrategy> strategies;
    private final Clock clock;

    /**
     * Simulates taking {@code req.assetId()} offline between {@code maintenanceStart} and
     * {@code maintenanceEnd} using the real {@link SubstituteStrategy} chain.
     */
    public MaintenanceImpactResponse simulate(SimulateMaintenanceRequest req) {
        LocalDateTime start = req.maintenanceStart();
        LocalDateTime end = req.maintenanceEnd();
        if (!end.isAfter(start)) {
            throw new BusinessRuleException("INVALID_TIME_RANGE", "Maintenance end time must be after start time");
        }

        AssetSummary targetAsset = catalogApi.getAsset(req.assetId());
        LocalDateTime now = LocalDateTime.now(clock);

        List<ReservationSummary> affected = reservationApi.findUpcomingActiveByAsset(req.assetId(), now).stream()
                .filter(r -> r.start().isBefore(end) && r.end().isAfter(start))
                .sorted(Comparator.comparing(ReservationSummary::start))
                .toList();

        List<SimulatedReassignmentItem> items = new ArrayList<>();
        int reassignedCount = 0;
        int forceCancelCount = 0;

        for (ReservationSummary booking : affected) {
            AssetSummary chosen = null;
            String strategyUsed = null;

            for (SubstituteStrategy strategy : strategies) {
                Optional<AssetSummary> candidateOpt = strategy.findSubstitute(booking, targetAsset);
                if (candidateOpt.isPresent()) {
                    chosen = candidateOpt.get();
                    strategyUsed = strategy.name();
                    break;
                }
            }

            if (chosen != null) {
                reassignedCount++;
                items.add(new SimulatedReassignmentItem(
                        booking.id(),
                        booking.userId(),
                        booking.start(),
                        booking.end(),
                        ReassignmentOutcome.REASSIGNED,
                        chosen.id(),
                        chosen.name(),
                        strategyUsed));
            } else {
                forceCancelCount++;
                items.add(new SimulatedReassignmentItem(
                        booking.id(),
                        booking.userId(),
                        booking.start(),
                        booking.end(),
                        ReassignmentOutcome.FORCE_CANCELLED,
                        null,
                        null,
                        null));
            }
        }

        return new MaintenanceImpactResponse(
                targetAsset.id(),
                targetAsset.name(),
                start,
                end,
                items.size(),
                reassignedCount,
                forceCancelCount,
                items);
    }
}

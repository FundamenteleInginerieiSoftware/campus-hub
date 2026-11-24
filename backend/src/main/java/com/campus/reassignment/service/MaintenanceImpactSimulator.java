package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.web.MaintenanceImpactResponse;
import com.campus.reassignment.web.SimulateMaintenanceRequest;
import com.campus.reassignment.web.SimulatedBookingImpact;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dry-run maintenance impact simulator (#58): previews which upcoming {@code CONFIRMED} bookings
 * overlapping a planned maintenance window would be reassigned (reusing the ordered
 * {@link SubstituteStrategy} beans) vs. force-cancelled if the asset were taken offline, without
 * mutating the database.
 *
 * <p>Scope note: unlike the live reassignment engine (which moves every future {@code CONFIRMED}
 * booking on damage or retirement), this dry-run considers only future {@code CONFIRMED} bookings
 * overlapping {@code [windowStart, windowEnd]} via {@link ReservationApi#findUpcomingActiveByAsset};
 * currently {@code CHECKED_OUT} or {@code OVERDUE} holdings are ignored.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenanceImpactSimulator {

    static final long MAX_WINDOW_DAYS = 90;
    private static final int REASSIGNED_WEIGHT = 1;
    private static final int FORCE_CANCELLED_WEIGHT = 3;

    private final CatalogApi catalogApi;
    private final ReservationApi reservationApi;
    private final List<SubstituteStrategy> strategies;
    private final Clock clock;

    private record TimeSlot(LocalDateTime start, LocalDateTime end) {
        boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return start.isBefore(otherEnd) && end.isAfter(otherStart);
        }
    }

    /**
     * Simulates taking {@code request.assetId()} offline across {@code [windowStart, windowEnd]}
     * (at most 90 days), evaluating only future {@code CONFIRMED} bookings that overlap the window.
     */
    public MaintenanceImpactResponse simulate(SimulateMaintenanceRequest request) {
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new BusinessRuleException("INVALID_TIME_RANGE", "Maintenance window end must be after start");
        }
        if (Duration.between(request.windowStart(), request.windowEnd()).compareTo(Duration.ofDays(MAX_WINDOW_DAYS))
                > 0) {
            throw new BusinessRuleException(
                    "INVALID_TIME_RANGE", "Maintenance window cannot exceed " + MAX_WINDOW_DAYS + " days");
        }

        AssetSummary target = catalogApi.getAsset(request.assetId());
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        List<ReservationSummary> affected = reservationApi.findUpcomingActiveByAsset(target.id(), now).stream()
                .filter(b -> b.start().isBefore(request.windowEnd()) && b.end().isAfter(request.windowStart()))
                .sorted(Comparator.comparing(ReservationSummary::start).thenComparing(ReservationSummary::id))
                .toList();

        // Keep an in-memory map of virtually assigned substitute slots so overlapping bookings
        // during the same dry run never get assigned to the same substitute unit.
        Map<Long, List<TimeSlot>> virtualAllocations = new HashMap<>();
        List<SimulatedBookingImpact> items = new ArrayList<>();
        int reassignedCount = 0;
        int forceCancelledCount = 0;

        for (ReservationSummary booking : affected) {
            SimulatedBookingImpact impact = simulateSingleBooking(booking, target, virtualAllocations);
            items.add(impact);
            if (impact.outcome() == ReassignmentOutcome.REASSIGNED) {
                reassignedCount++;
            } else {
                forceCancelledCount++;
            }
        }

        int disruptionScore = reassignedCount * REASSIGNED_WEIGHT + forceCancelledCount * FORCE_CANCELLED_WEIGHT;
        return new MaintenanceImpactResponse(
                target.id(),
                target.name(),
                request.windowStart(),
                request.windowEnd(),
                items.size(),
                reassignedCount,
                forceCancelledCount,
                disruptionScore,
                items);
    }

    private SimulatedBookingImpact simulateSingleBooking(
            ReservationSummary booking, AssetSummary target, Map<Long, List<TimeSlot>> virtualAllocations) {
        for (SubstituteStrategy strategy : strategies) {
            for (AssetSummary candidate : strategy.findCandidates(booking, target)) {
                List<TimeSlot> taken = virtualAllocations.getOrDefault(candidate.id(), List.of());
                boolean alreadyAssigned =
                        taken.stream().anyMatch(slot -> slot.overlaps(booking.start(), booking.end()));
                if (alreadyAssigned) {
                    continue;
                }
                virtualAllocations
                        .computeIfAbsent(candidate.id(), ignored -> new ArrayList<>())
                        .add(new TimeSlot(booking.start(), booking.end()));
                return new SimulatedBookingImpact(
                        booking.id(),
                        booking.userId(),
                        booking.start(),
                        booking.end(),
                        ReassignmentOutcome.REASSIGNED,
                        candidate.id(),
                        candidate.name(),
                        strategy.name());
            }
        }
        return new SimulatedBookingImpact(
                booking.id(),
                booking.userId(),
                booking.start(),
                booking.end(),
                ReassignmentOutcome.FORCE_CANCELLED,
                null,
                null,
                null);
    }
}

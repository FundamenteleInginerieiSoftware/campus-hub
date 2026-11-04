package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes automated disaster-recovery reassignment or force-cancellation for a single
 * reservation inside an isolated {@link Propagation#REQUIRES_NEW} transaction.
 *
 * <p>Bug fixed: previously, the entire reassignment loop in {@link ReassignmentService} executed
 * within a single transaction. If one reservation encountered an optimistic locking conflict (e.g.
 * cancelled concurrently by the student or released by the no-show sweep), the entire batch rolled
 * back even though notifications had already been dispatched. With isolated per-booking transactions,
 * each booking commits independently, and notifications are only dispatched after successful commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ReassignmentBookingProcessor {

    private final ReservationApi reservationApi;
    private final ReassignmentLogRepository reassignmentLogRepository;
    private final List<SubstituteStrategy> strategies;
    private final Clock clock;

    record ReassignmentExecutionResult(
            ReassignmentOutcome outcome, Long substituteAssetId, String substituteAssetName, String strategyName) {}

    /**
     * Processes a single reservation on a damaged asset in an isolated transaction.
     *
     * @return {@link Optional} containing execution details if moved or force-cancelled, or empty if skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ReassignmentExecutionResult> processSingleBooking(
            AssetDamagedEvent event, ReservationSummary booking, AssetSummary damaged) {
        boolean reassigned = false;

        for (SubstituteStrategy strategy : strategies) {
            Optional<AssetSummary> substituteOpt = strategy.findSubstitute(booking, damaged);
            if (substituteOpt.isPresent()) {
                AssetSummary candidate = substituteOpt.get();
                try {
                    boolean moved =
                            reservationApi.moveToAsset(booking.id(), candidate.id(), "Incident #" + event.incidentId());
                    reassigned = true;

                    if (moved) {
                        ReassignmentLog logEntry = new ReassignmentLog(
                                event.incidentId(),
                                booking.id(),
                                event.assetId(),
                                candidate.id(),
                                ReassignmentOutcome.REASSIGNED,
                                strategy.name(),
                                LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
                        reassignmentLogRepository.save(logEntry);

                        return Optional.of(new ReassignmentExecutionResult(
                                ReassignmentOutcome.REASSIGNED, candidate.id(), candidate.name(), strategy.name()));
                    }
                    // Reservation was no longer CONFIRMED when moveToAsset ran; skip it.
                    return Optional.empty();
                } catch (SubstituteNoLongerFreeException e) {
                    log.warn(
                            "Candidate asset {} was no longer free when moving reservation #{}, falling back to next strategy",
                            candidate.id(),
                            booking.id());
                }
            }
        }

        if (!reassigned) {
            boolean cancelled =
                    reservationApi.forceCancel(booking.id(), "Equipment failure, incident #" + event.incidentId());

            if (cancelled) {
                ReassignmentLog logEntry = new ReassignmentLog(
                        event.incidentId(),
                        booking.id(),
                        event.assetId(),
                        null,
                        ReassignmentOutcome.FORCE_CANCELLED,
                        null,
                        LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
                reassignmentLogRepository.save(logEntry);

                return Optional.of(
                        new ReassignmentExecutionResult(ReassignmentOutcome.FORCE_CANCELLED, null, null, null));
            }
        }

        return Optional.empty();
    }
}

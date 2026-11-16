package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes automated disaster-recovery reassignment or force-cancellation for a single
 * reservation inside isolated {@code REQUIRES_NEW} transactions.
 *
 * <p>Bug fixed (#112): previously, the entire reassignment loop in {@link ReassignmentService} executed
 * within a single transaction. When one reservation encountered an optimistic locking conflict (e.g.
 * cancelled concurrently in another transaction), the engine's L1 Hibernate session held a stale
 * cached entity whose status still appeared CONFIRMED; when flushing, Hibernate detected the @Version
 * mismatch and threw an ObjectOptimisticLockingFailureException that rolled back the entire batch,
 * even though student notification emails had already been dispatched.
 *
 * <p>Bug fixed (#149): previously, {@code processSingleBooking} started the {@code REQUIRES_NEW}
 * transaction before calling {@code strategy.findSubstitute}, which loaded candidate {@code Asset}
 * entities into the L1 persistence context without a row lock before {@code moveToAsset} called
 * {@code lockForBooking}. In addition, if the first candidate threw {@link SubstituteNoLongerFreeException},
 * the processor immediately abandoned the current strategy instead of trying remaining free candidates
 * of the same strategy.
 *
 * <p>Fix: candidate discovery runs outside the write transaction, and each candidate move attempt (or
 * final force-cancellation) runs in its own fresh {@code REQUIRES_NEW} persistence context so
 * {@code lockForBooking} is always the first touch of {@code Asset}.
 */
@Slf4j
@Service
class ReassignmentBookingProcessor {

    private final ReservationApi reservationApi;
    private final ReassignmentLogRepository reassignmentLogRepository;
    private final List<SubstituteStrategy> strategies;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    @Autowired
    ReassignmentBookingProcessor(
            ReservationApi reservationApi,
            ReassignmentLogRepository reassignmentLogRepository,
            List<SubstituteStrategy> strategies,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.reservationApi = reservationApi;
        this.reassignmentLogRepository = reassignmentLogRepository;
        this.strategies = strategies;
        this.clock = clock;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    record ReassignmentExecutionResult(ReassignmentOutcome outcome, String substituteAssetName) {}

    /**
     * Processes a single reservation on a damaged asset, trying all candidates of each strategy
     * in order before falling back to force-cancellation.
     *
     * @return {@link Optional} containing execution details if moved or force-cancelled, or empty if skipped.
     */
    public Optional<ReassignmentExecutionResult> processSingleBooking(
            AssetDamagedEvent event, ReservationSummary booking, AssetSummary damaged) {
        Set<Long> attemptedCandidateIds = new HashSet<>();
        for (SubstituteStrategy strategy : strategies) {
            List<AssetSummary> candidates = strategy.findCandidates(booking, damaged);
            for (AssetSummary candidate : candidates) {
                // Bug fixed (#149): skip candidates that were already tried and threw in an earlier strategy
                if (!attemptedCandidateIds.add(candidate.id())) {
                    continue;
                }
                try {
                    return inRequiresNewTransaction(() -> {
                        boolean moved = reservationApi.moveToAsset(
                                booking.id(), candidate.id(), "Incident #" + event.incidentId());

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

                            return Optional.of(
                                    new ReassignmentExecutionResult(ReassignmentOutcome.REASSIGNED, candidate.name()));
                        }
                        // Reservation was no longer CONFIRMED when moveToAsset ran; skip it cleanly.
                        return Optional.empty();
                    });
                } catch (SubstituteNoLongerFreeException e) {
                    log.warn(
                            "Candidate asset {} was no longer free when moving reservation #{}, trying next candidate",
                            candidate.id(),
                            booking.id(),
                            e);
                } catch (RuntimeException e) {
                    // Bug fixed (#267): a lock/version conflict now tries the next candidate. Any other error is
                    // rethrown (ReassignmentService.handle logs it), so a bug never force-cancels a student's booking.
                    if (!isConcurrencyConflict(e)) {
                        throw e;
                    }
                    log.warn(
                            "Concurrency conflict moving reservation #{} to candidate asset {}, trying next candidate",
                            booking.id(),
                            candidate.id(),
                            e);
                }
            }
        }

        // If no strategy found a free substitute, force-cancel the reservation in a fresh REQUIRES_NEW transaction
        return inRequiresNewTransaction(() -> {
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

                return Optional.of(new ReassignmentExecutionResult(ReassignmentOutcome.FORCE_CANCELLED, null));
            }

            return Optional.empty();
        });
    }

    private <T> T inRequiresNewTransaction(Supplier<T> action) {
        return requiresNewTx.execute(status -> action.get());
    }

    private static boolean isConcurrencyConflict(Throwable t) {
        if (t instanceof SubstituteNoLongerFreeException || t instanceof ConcurrencyFailureException) {
            return true;
        }
        Throwable cause = t.getCause();
        while (cause != null) {
            if (cause instanceof ConcurrencyFailureException || cause instanceof SubstituteNoLongerFreeException) {
                return true;
            }
            if (cause instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if ("40P01".equals(sqlState) || "55P03".equals(sqlState) || "40001".equals(sqlState)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}

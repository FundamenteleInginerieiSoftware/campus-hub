package com.campus.reassignment.service;

import com.campus.catalog.api.AssetRetiredEvent;
import com.campus.catalog.api.AssetSummary;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.domain.ReassignmentTrigger;
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

    static final int MAX_TRANSIENT_CONCURRENCY_RETRIES = 2;
    private static final long RETRY_BACKOFF_BASE_MS = 25L;

    record ReassignmentExecutionResult(ReassignmentOutcome outcome, String substituteAssetName, Long ownerUserId) {}

    /**
     * Processes a single reservation on a damaged asset, trying all candidates of each strategy
     * in order before falling back to force-cancellation.
     *
     * @return {@link Optional} containing execution details if moved or force-cancelled, or empty if skipped.
     */
    public Optional<ReassignmentExecutionResult> processSingleBooking(
            AssetDamagedEvent event, ReservationSummary booking, AssetSummary damaged) {
        return processSingleBooking(
                event.incidentId(),
                ReassignmentTrigger.INCIDENT,
                event.assetId(),
                "Incident #" + event.incidentId(),
                "Equipment failure, incident #" + event.incidentId(),
                booking,
                damaged);
    }

    /**
     * Processes a single reservation on a retired asset (#245), trying all candidates of each strategy
     * in order before falling back to force-cancellation. Logs {@code incidentId = null} and
     * {@code trigger = RETIRED}.
     *
     * @return {@link Optional} containing execution details if moved or force-cancelled, or empty if skipped.
     */
    public Optional<ReassignmentExecutionResult> processSingleBooking(
            AssetRetiredEvent event, ReservationSummary booking, AssetSummary retired) {
        return processSingleBooking(
                null,
                ReassignmentTrigger.RETIRED,
                event.assetId(),
                "Asset retired #" + event.assetId(),
                "Equipment retired (asset #" + event.assetId() + ")",
                booking,
                retired);
    }

    private Optional<ReassignmentExecutionResult> processSingleBooking(
            Long incidentId,
            ReassignmentTrigger trigger,
            Long sourceAssetId,
            String moveReason,
            String cancelReason,
            ReservationSummary booking,
            AssetSummary sourceAsset) {
        Set<Long> attemptedCandidateIds = new HashSet<>();
        for (SubstituteStrategy strategy : strategies) {
            List<AssetSummary> candidates = strategy.findCandidates(booking, sourceAsset);
            for (AssetSummary candidate : candidates) {
                // Bug fixed (#149): skip candidates that were already tried and threw in an earlier strategy
                if (!attemptedCandidateIds.add(candidate.id())) {
                    continue;
                }
                for (int attempt = 0; ; attempt++) {
                    try {
                        return inRequiresNewTransaction(() -> {
                            Optional<Long> movedOwnerId =
                                    reservationApi.moveToAsset(booking.id(), sourceAssetId, candidate.id(), moveReason);

                            if (movedOwnerId.isPresent()) {
                                ReassignmentLog logEntry = new ReassignmentLog(
                                        incidentId,
                                        trigger,
                                        booking.id(),
                                        sourceAssetId,
                                        candidate.id(),
                                        ReassignmentOutcome.REASSIGNED,
                                        strategy.name(),
                                        LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
                                reassignmentLogRepository.save(logEntry);

                                return Optional.of(new ReassignmentExecutionResult(
                                        ReassignmentOutcome.REASSIGNED, candidate.name(), movedOwnerId.get()));
                            }
                            // Reservation was no longer CONFIRMED or already moved off sourceAssetId (#319); skip
                            // cleanly.
                            return Optional.empty();
                        });
                    } catch (SubstituteNoLongerFreeException e) {
                        log.warn(
                                "Candidate asset {} was no longer free when moving reservation #{}, trying next candidate",
                                candidate.id(),
                                booking.id(),
                                e);
                        break;
                    } catch (RuntimeException e) {
                        if (isWrappedSubstituteNoLongerFree(e)) {
                            log.warn(
                                    "Candidate asset {} was no longer free when moving reservation #{}, trying next candidate",
                                    candidate.id(),
                                    booking.id(),
                                    e);
                            break;
                        }
                        // Bug fixed (#362 §2.2): previously, catching ConcurrencyFailureException fell through
                        // to forceCancel when no other candidates existed, permanently cancelling a booking whose
                        // substitute was actually free. Fix: retry the same candidate up to 2 times with brief
                        // backoff, and if transient lock/deadlock errors persist, rethrow so ReassignmentService
                        // logs and skips rather than permanently force-cancelling the student's reservation.
                        if (isTransientConcurrencyError(e) && attempt < MAX_TRANSIENT_CONCURRENCY_RETRIES) {
                            log.warn(
                                    "Transient concurrency error moving reservation #{} to candidate asset {} (attempt {}/{}), retrying",
                                    booking.id(),
                                    candidate.id(),
                                    attempt + 1,
                                    MAX_TRANSIENT_CONCURRENCY_RETRIES + 1,
                                    e);
                            if (!backoffBeforeRetry(attempt)) {
                                throw e;
                            }
                            continue;
                        }
                        throw e;
                    }
                }
            }
        }

        // If no strategy found a free substitute, force-cancel the reservation in a fresh REQUIRES_NEW transaction
        return inRequiresNewTransaction(() -> {
            Optional<Long> cancelledOwnerId = reservationApi.forceCancel(booking.id(), sourceAssetId, cancelReason);

            if (cancelledOwnerId.isPresent()) {
                ReassignmentLog logEntry = new ReassignmentLog(
                        incidentId,
                        trigger,
                        booking.id(),
                        sourceAssetId,
                        null,
                        ReassignmentOutcome.FORCE_CANCELLED,
                        null,
                        LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
                reassignmentLogRepository.save(logEntry);

                return Optional.of(new ReassignmentExecutionResult(
                        ReassignmentOutcome.FORCE_CANCELLED, null, cancelledOwnerId.get()));
            }

            return Optional.empty();
        });
    }

    private <T> T inRequiresNewTransaction(Supplier<T> action) {
        return requiresNewTx.execute(status -> action.get());
    }

    private static boolean backoffBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_BASE_MS * (attempt + 1L));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isWrappedSubstituteNoLongerFree(Throwable t) {
        Throwable cause = t.getCause();
        while (cause != null) {
            if (cause instanceof SubstituteNoLongerFreeException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isTransientConcurrencyError(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof ConcurrencyFailureException) {
                return true;
            }
            if (current instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if ("40P01".equals(sqlState) || "55P03".equals(sqlState) || "40001".equals(sqlState)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}

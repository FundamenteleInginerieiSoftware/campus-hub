package com.campus.reassignment.service.chain;

import com.campus.common.error.ConflictException;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Atomically executes all relocation steps in an augmenting chain shift.
 *
 * <p>Separated from the planning phase into an isolated Spring service bean so that:
 * 1. Read-only queries during graph search do not load un-locked entities into Hibernate's L1 cache;
 * 2. Execution runs in a dedicated {@code @Transactional} context that rolls back atomically if any step fails;
 * 3. Student notifications are dispatched strictly after successful transaction commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainShiftExecutor {

    private final ReservationApi reservationApi;
    private final ReassignmentLogRepository reassignmentLogRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    @Transactional
    public void executeChainShift(ChainShiftPlan plan) {
        if (!plan.resolved() || plan.steps().isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        for (ChainShiftStep step : plan.steps()) {
            boolean moved;
            try {
                moved = reservationApi.moveToAsset(
                        step.reservationId(), step.toAssetId(), "CHAIN_SHIFT: " + step.strategyName());
            } catch (SubstituteNoLongerFreeException e) {
                log.warn(
                        "Chain shift step failed to move reservation #{} to asset #{}: {}",
                        step.reservationId(),
                        step.toAssetId(),
                        e.getMessage());
                moved = false;
            }

            if (!moved) {
                throw new ConflictException(
                        "CHAIN_SHIFT_EXECUTION_FAILED",
                        "One or more reservations in the chain could not be moved (slot conflict or state changed).");
            }

            ReassignmentLog logEntry = new ReassignmentLog(
                    null, // null indicates non-incident administrative chain shift
                    step.reservationId(),
                    step.fromAssetId(),
                    step.toAssetId(),
                    ReassignmentOutcome.REASSIGNED,
                    "ChainShift:" + step.strategyName(),
                    now);
            reassignmentLogRepository.save(logEntry);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifyStudents(plan);
                }
            });
        } else {
            notifyStudents(plan);
        }
    }

    private void notifyStudents(ChainShiftPlan plan) {
        for (ChainShiftStep step : plan.steps()) {
            try {
                notificationService.notifyUser(
                        step.userId(),
                        "Reservation Reassigned",
                        "Your reservation #" + step.reservationId() + " has been relocated to " + step.toAssetName()
                                + " via equipment balancing.");
            } catch (Exception e) {
                log.error(
                        "Failed to deliver chain shift notification to user {} for reservation #{}: {}",
                        step.userId(),
                        step.reservationId(),
                        e.getMessage());
            }
        }
    }
}

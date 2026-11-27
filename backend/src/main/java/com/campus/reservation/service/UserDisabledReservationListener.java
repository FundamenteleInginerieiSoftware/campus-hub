package com.campus.reservation.service;

import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserDisabledEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to {@link UserDisabledEvent} (#229, #302, #349) by cancelling the disabled account's
 * upcoming {@code CONFIRMED} reservations and active {@code WAITING} waitlist entries.
 *
 * <p>Runs {@code AFTER_COMMIT} in two separate {@code REQUIRES_NEW} transactions via
 * {@link TransactionTemplate}, each inside its own {@code try / catch (RuntimeException)}:
 * <ol>
 *   <li>Step 1 acquires {@code lockUserQuota(userId)} first, re-checks {@code userApi.findActiveRole(userId)}
 *       (skipping cancellation if the account was already re-enabled), and cancels upcoming reservations.
 *       Holding {@code lockUserQuota} here acts as a barrier draining any in-flight {@code joinWaitlist}
 *       before Step 2 scans {@code waitlist_entries}, closing the orphan-{@code WAITING} race (#349).</li>
 *   <li>Step 2 cancels {@code WAITING} waitlist entries in its own {@code REQUIRES_NEW} transaction
 *       without {@code lockUserQuota} so it never deadlocks with waitlist promotion and cannot roll back
 *       Step 1's booking cancellations.</li>
 * </ol>
 * Deliberately not {@code @Async}: the audit actor comes from {@code CurrentUser}, which is bound
 * to the HTTP request thread.
 */
@Slf4j
@Service
public class UserDisabledReservationListener {

    static final String DEFAULT_REASON = "Account disabled by administrator";

    private final ReservationApi reservationApi;
    private final ReservationRepository reservationRepository;
    private final UserApi userApi;
    private final WaitlistService waitlistService;
    private final TransactionTemplate requiresNewTx;

    public UserDisabledReservationListener(
            ReservationApi reservationApi,
            ReservationRepository reservationRepository,
            UserApi userApi,
            WaitlistService waitlistService,
            PlatformTransactionManager transactionManager) {
        this.reservationApi = reservationApi;
        this.reservationRepository = reservationRepository;
        this.userApi = userApi;
        this.waitlistService = waitlistService;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDisabled(UserDisabledEvent event) {
        // Step 1: cancel upcoming CONFIRMED reservations FIRST in its own REQUIRES_NEW transaction.
        // Acquiring lockUserQuota(userId) here drains any in-flight joinWaitlist / createReservation
        // before Step 2 scans waitlist_entries, and re-checking findActiveRole under the lock closes
        // the disable -> immediate re-enable race (#349).
        AtomicBoolean reEnabled = new AtomicBoolean(false);
        try {
            requiresNewTx.executeWithoutResult(status -> {
                reservationRepository.lockUserQuota(event.userId());
                if (userApi.findActiveRole(event.userId()).isPresent()) {
                    reEnabled.set(true);
                    log.info(
                            "Skipping disable cleanup for user #{} because the account was re-enabled before listener ran",
                            event.userId());
                    return;
                }
                int cancelled = reservationApi.cancelUpcomingReservationsForUser(event.userId(), DEFAULT_REASON);
                log.info(
                        "Cancelled {} upcoming reservation(s) for disabled user #{} (disabled by admin #{})",
                        cancelled,
                        event.userId(),
                        event.adminId());
            });
        } catch (RuntimeException ex) {
            // Never propagate: disabling the account is the security kill switch and already committed.
            // An admin can retry via POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming.
            log.error(
                    "Failed to cancel upcoming reservations for disabled user #{} (admin #{}); "
                            + "admin can retry via POST /api/v1/admin/reservations/users/{}/cancel-upcoming",
                    event.userId(),
                    event.adminId(),
                    event.userId(),
                    ex);
        }

        if (reEnabled.get()) {
            return;
        }

        // Step 2: cancel WAITING waitlist entries in a separate REQUIRES_NEW transaction (no lockUserQuota)
        // so waitlist rows never share a transaction with lockUserQuota (avoiding a lock-order deadlock
        // with waitlist promotion) and a waitlist failure cannot roll back Step 1 (#349).
        try {
            requiresNewTx.executeWithoutResult(status -> {
                int cancelledWaitlist = waitlistService.cancelWaitingEntriesForUser(event.userId());
                log.info(
                        "Cancelled {} waitlist entry(ies) for disabled user #{} (disabled by admin #{})",
                        cancelledWaitlist,
                        event.userId(),
                        event.adminId());
            });
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to cancel waitlist entries for disabled user #{} (admin #{}): {}",
                    event.userId(),
                    event.adminId(),
                    ex.getMessage(),
                    ex);
        }
    }
}

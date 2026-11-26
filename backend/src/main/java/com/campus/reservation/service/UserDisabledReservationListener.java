package com.campus.reservation.service;

import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserDisabledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to {@link UserDisabledEvent} (#229, #302, #349) by cancelling the disabled account's
 * active {@code WAITING} waitlist entries and upcoming {@code CONFIRMED} reservations.
 *
 * <p>Runs {@code AFTER_COMMIT} in two separate {@code REQUIRES_NEW} transactions via
 * {@link TransactionTemplate}, each inside its own {@code try / catch (RuntimeException)}:
 * waitlist cleanup runs first (without {@code lockUserQuota}) so it never deadlocks with waitlist
 * promotion and cannot roll back the upcoming reservation cancellations below, while the retry log
 * line ({@code POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming}) still triggers if
 * reservation cancellation fails. Deliberately not {@code @Async}: the audit actor comes from
 * {@code CurrentUser}, which is bound to the HTTP request thread.
 *
 * <p>Bug fixed (#362 §3): acquires {@code lockUserQuota(userId)} and re-checks {@code userApi.findActiveRole}
 * before cancelling reservations so an immediate disable -> re-enable race never wipes bookings of a re-enabled account.
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
        // Step 1: cancel WAITING waitlist entries in its own REQUIRES_NEW transaction (no lockUserQuota)
        // BEFORE reservation cancellation, so waitlist rows never share a transaction with lockUserQuota
        // (avoiding a lock-order deadlock with waitlist promotion) and a waitlist error never rolls back
        // the upcoming-booking cancellations below (#349).
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

        // Step 2: cancel upcoming CONFIRMED reservations in a separate REQUIRES_NEW transaction.
        try {
            requiresNewTx.executeWithoutResult(status -> {
                // Lock user quota first so any in-flight booking drains before we check active status
                // and skip cancellation if an admin already re-enabled the account (#362 §3).
                reservationRepository.lockUserQuota(event.userId());
                if (userApi.findActiveRole(event.userId()).isPresent()) {
                    log.info(
                            "Skipping reservation cancellation for user #{} because account was re-enabled before listener ran",
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
    }
}

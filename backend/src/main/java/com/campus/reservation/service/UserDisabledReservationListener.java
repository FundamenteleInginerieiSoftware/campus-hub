package com.campus.reservation.service;

import com.campus.reservation.api.ReservationApi;
import com.campus.user.api.UserDisabledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to {@link UserDisabledEvent} (#229, #302) by cancelling the disabled account's upcoming
 * {@code CONFIRMED} reservations via {@link ReservationApi#cancelUpcomingReservationsForUser}.
 *
 * <p>Runs {@code AFTER_COMMIT} in a fresh {@code REQUIRES_NEW} transaction via {@link TransactionTemplate}
 * inside {@code try / catch (RuntimeException)}: that way both business errors and commit/flush failures
 * land in the {@code catch} block, roll back the inner transaction cleanly, and emit the actionable
 * retry log line ({@code POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming}). Deliberately
 * not {@code @Async}: the audit actor comes from {@code CurrentUser}, which is bound to the HTTP request
 * thread.
 */
@Slf4j
@Service
public class UserDisabledReservationListener {

    static final String DEFAULT_REASON = "Account disabled by administrator";

    private final ReservationApi reservationApi;
    private final TransactionTemplate requiresNewTx;

    public UserDisabledReservationListener(
            ReservationApi reservationApi, PlatformTransactionManager transactionManager) {
        this.reservationApi = reservationApi;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDisabled(UserDisabledEvent event) {
        try {
            requiresNewTx.executeWithoutResult(status -> {
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

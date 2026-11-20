package com.campus.user.api;

/**
 * Published by {@code AdminUserService.disable} when an admin disables an account (#229, #302). It is published
 * inside the disable transaction, and only when the account really changes from enabled to disabled.
 *
 * <p>Disabling an account is the security kill switch, so a listener must never be able to undo it. Listeners
 * therefore use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}: they run only after the disable has
 * been saved. By then that transaction is already finished, so a listener that simply joined it would have its
 * writes never committed. It must open a fresh transaction with {@code REQUIRES_NEW} (Calin's
 * {@code UserDisabledReservationListener} does this with a {@code TransactionTemplate}). Spring already logs and
 * swallows an exception thrown from an AFTER_COMMIT listener, so the disable cannot fail anyway; the listener
 * still catches its own errors to log the admin retry hint (see below).
 *
 * <p>Unlike {@code AssetDamagedEvent} and {@code AssetRetiredEvent}, whose reassignment listeners are
 * {@code @Async}, these listeners are not {@code @Async}: they run on the admin's request thread, because the
 * audit actor comes from {@code CurrentUser}, which is lost on another thread.
 *
 * <p>The first listener is the reservation module, which cancels the user's upcoming bookings
 * ({@code ReservationApi.cancelUpcomingReservationsForUser}, #266). If it fails, the account stays disabled and
 * an admin can run the same cancel again with {@code POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming}.
 *
 * <p>It lives in {@code user.api} and not in {@code user.admin} because other modules may depend only on
 * {@code api} packages; {@code reservation} already depends on {@code user}, so a direct call from user admin
 * into {@code reservation} would create a cycle (ArchUnit {@code no_cycles_between_modules}).
 *
 * @param userId the disabled account
 * @param adminId the admin who disabled it
 */
public record UserDisabledEvent(Long userId, Long adminId) {}

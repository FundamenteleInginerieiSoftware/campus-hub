package com.campus.user.api;

/**
 * Published by {@code AdminUserService.disable} when an admin disables an account (#229, #302). It is published
 * inside the disable transaction, and only when the account really changes from enabled to disabled.
 *
 * <p>Disabling an account is the security kill switch, so it must never fail because of a listener. Listeners
 * therefore use {@code @TransactionalEventListener(phase = AFTER_COMMIT)} together with
 * {@code @Transactional(propagation = REQUIRES_NEW)}, catch and log their own errors, and are not {@code @Async}
 * (the audit actor comes from {@code CurrentUser}, which is lost on another thread). This is the same contract as
 * {@code AssetDamagedEvent} and {@code AssetRetiredEvent}.
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

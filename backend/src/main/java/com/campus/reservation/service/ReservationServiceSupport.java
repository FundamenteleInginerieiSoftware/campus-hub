package com.campus.reservation.service;

import com.campus.common.error.ForbiddenException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Package-private utilities shared across {@link ReservationService}, {@link HoldService},
 * and {@link TransferService} (whole-second clock reading, after-commit notifications, and locked ownership lookup).
 */
@Slf4j
final class ReservationServiceSupport {

    private ReservationServiceSupport() {}

    // Bug fixed (#123): whole seconds, like the contract format (2026-11-03T14:00:00), so POST and GET
    // return consistent values without subsecond drift between API responses and PostgreSQL storage.
    // Note: because boundary comparisons (e.g. past check) use this truncated now, a booking starting at
    // 10:00:00 requested at 10:00:00.9 is accepted within the same second rather than being flagged as BOOKING_IN_PAST.
    static LocalDateTime truncatedNow(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Dispatches notification to the recipient strictly after the active database transaction commits (#240 point 2).
     * Prevents phantom notifications in production (e.g. SES) if an operation subsequently rolls back.
     */
    static void notifyUserAfterCommit(
            NotificationService notificationService, Long userId, String subject, String body) {
        Runnable task = () -> {
            try {
                notificationService.notifyUser(userId, subject, body);
            } catch (Exception e) {
                log.warn("Failed to notify user {} of '{}': {}", userId, subject, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    static Reservation loadOwnedOrAdmin(ReservationRepository reservationRepository, CurrentUser currentUser, Long id) {
        Reservation reservation = reservationRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id));

        if (!reservation.getUserId().equals(currentUser.id()) && !currentUser.isAdmin()) {
            throw new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id);
        }

        return reservation;
    }

    static Reservation loadOwnedOrAdminForUpdate(
            ReservationRepository reservationRepository, CurrentUser currentUser, Long id) {
        Reservation reservation = reservationRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id));

        if (!reservation.getUserId().equals(currentUser.id()) && !currentUser.isAdmin()) {
            throw new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id);
        }

        return reservation;
    }

    static Long currentUserIdOrNull(CurrentUser currentUser) {
        try {
            return currentUser.id();
        } catch (Exception ignored) {
            // Background execution or non-web context
            return null;
        }
    }

    // Bug fixed (#150): prevents integer overflow when Hibernate casts pageable.getOffset() to int.
    static void validatePagination(int page, int size) {
        if (page < 0) {
            throw new InvalidParameterException("Page index must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new InvalidParameterException("Page size must be between 1 and 100");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidParameterException("Page offset exceeds maximum supported value");
        }
    }

    // Bug fixed (#229): a booking/hold/transfer request that passed the JWT check right before an admin
    // disabled the account can block on lockUserQuota(userId), wait for cancelUpcomingReservationsForUser
    // to finish, and then insert a new CONFIRMED booking for the disabled user. Re-checking findActiveRole
    // right after acquiring the per-user advisory lock closes that race window.
    static Role requireActiveUserAfterLock(UserApi userApi, Long userId) {
        return userApi.findActiveRole(userId)
                .orElseThrow(() -> new ForbiddenException("USER_DISABLED", "Account is disabled or no longer active"));
    }

    static void confirmOwnedHoldsForSlots(
            ReservationHoldRepository reservationHoldRepository,
            Long userId,
            Collection<Long> assetIds,
            LocalDateTime start,
            LocalDateTime end,
            LocalDateTime now) {
        if (assetIds == null || assetIds.isEmpty()) {
            return;
        }
        // Lock overlapping own holds FOR UPDATE ORDER BY id ASC (last in Quota -> Reservation -> Asset -> Transfer ->
        // Hold)
        // so concurrent releaseHold waits instead of tripping @Version and partial-overlap holds are confirmed (#362).
        reservationHoldRepository
                .findActiveOverlappingForUserAndAssetsForUpdate(userId, assetIds, start, end, now)
                .stream()
                .filter(h -> assetIds.contains(h.getAssetId())
                        && h.getStartTime().isBefore(end)
                        && h.getEndTime().isAfter(start))
                .forEach(h -> {
                    h.confirm(now);
                    reservationHoldRepository.save(h);
                });
    }
}

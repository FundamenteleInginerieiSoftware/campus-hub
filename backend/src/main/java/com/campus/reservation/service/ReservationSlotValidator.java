package com.campus.reservation.service;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Shared validation for reservation slot timing rules and slot-free checks across single bookings,
 * multi-asset bundles, hold acquisition, and hold confirmation so rules cannot drift.
 */
final class ReservationSlotValidator {

    static final LocalTime OPEN_TIME = LocalTime.of(8, 0);
    static final LocalTime CLOSE_TIME = LocalTime.of(20, 0);
    static final int MAX_ADVANCE_DAYS = 14;
    static final int MIN_DURATION_MINUTES = 30;
    static final int MAX_DURATION_MINUTES = 240;
    static final int SLOT_STEP_MINUTES = 30;

    private ReservationSlotValidator() {}

    /**
     * Validates reservation slot rules (time range, future start, :00/:30 alignment,
     * 30m-4h duration, 14-day horizon, and Mon-Fri 08:00-20:00 opening hours).
     */
    static void validateSlotRules(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (!end.isAfter(start)) {
            throw new BusinessRuleException("INVALID_TIME_RANGE", "End time must be after start time");
        }
        if (start.isBefore(now)) {
            throw new BusinessRuleException("BOOKING_IN_PAST", "Cannot book a reservation in the past");
        }
        validateSlotAlignment(start, end);
        validateDuration(start, end);
        validateHorizon(start, now);
        validateOpeningHours(start, end);
    }

    static void validateExtensionRules(LocalDateTime start, LocalDateTime newEnd, LocalDateTime now) {
        validateSlotAlignment(start, newEnd);
        validateDuration(start, newEnd);
        // Check horizon against start (same as validateSlotRules, #362 §4) so day-14 extensions remain consistent.
        validateHorizon(start, now);
        validateOpeningHours(start, newEnd);
    }

    /**
     * Acquires the pessimistic row lock on the asset, verifies it is bookable, and checks that
     * neither active reservations nor active holds by other students overlap the requested slot.
     */
    static void lockAndValidateSlotFree(
            CatalogApi catalogApi,
            ReservationRepository reservationRepository,
            ReservationHoldRepository reservationHoldRepository,
            Long assetId,
            LocalDateTime start,
            LocalDateTime end,
            LocalDateTime now,
            Long excludeHoldId,
            Long excludeHoldUserId) {
        // 1. Lock asset row first (pessimistic lock prevents race conditions on overlapping slots)
        catalogApi.lockForBooking(assetId);

        // 2. Operational bookable check
        ensureBookable(catalogApi, assetId, "Asset is not available for booking");

        // 3. Overlap check against active reservations and active holds from other students
        ensureNoSlotConflict(
                reservationRepository,
                reservationHoldRepository,
                assetId,
                start,
                end,
                now,
                excludeHoldId,
                excludeHoldUserId,
                "Requested slot overlaps with an existing reservation",
                "This time slot is temporarily held by another student");
    }

    static void ensureBookable(CatalogApi catalogApi, Long assetId, String message) {
        if (!catalogApi.isBookable(assetId)) {
            throw new ConflictException("ASSET_NOT_BOOKABLE", message);
        }
    }

    static void ensureNoSlotConflict(
            ReservationRepository reservationRepository,
            ReservationHoldRepository reservationHoldRepository,
            Long assetId,
            LocalDateTime start,
            LocalDateTime end,
            LocalDateTime now,
            Long excludeHoldId,
            Long excludeHoldUserId,
            String reservationConflictMessage,
            String holdConflictMessage) {
        if (reservationRepository.existsOverlap(assetId, start, end, ReservationService.NO_RESERVATION_ID)) {
            throw new ConflictException("RESERVATION_CONFLICT", reservationConflictMessage);
        }
        ensureNoActiveHoldOverlap(
                reservationHoldRepository,
                assetId,
                start,
                end,
                now,
                excludeHoldId,
                excludeHoldUserId,
                holdConflictMessage);
    }

    static void ensureNoActiveHoldOverlap(
            ReservationHoldRepository reservationHoldRepository,
            Long assetId,
            LocalDateTime start,
            LocalDateTime end,
            LocalDateTime now,
            Long excludeHoldId,
            Long excludeHoldUserId,
            String holdConflictMessage) {
        if (reservationHoldRepository.existsActiveOverlap(assetId, start, end, now, excludeHoldId, excludeHoldUserId)) {
            throw new ConflictException("SLOT_HELD", holdConflictMessage);
        }
    }

    private static void validateSlotAlignment(LocalDateTime start, LocalDateTime end) {
        if (start.getSecond() != 0
                || start.getNano() != 0
                || (start.getMinute() != 0 && start.getMinute() != SLOT_STEP_MINUTES)
                || end.getSecond() != 0
                || end.getNano() != 0
                || (end.getMinute() != 0 && end.getMinute() != SLOT_STEP_MINUTES)) {
            throw new BusinessRuleException(
                    "SLOT_NOT_ALIGNED",
                    "Reservation start and end times must be aligned to 0 or 30 minutes with zero seconds");
        }
    }

    private static void validateDuration(LocalDateTime start, LocalDateTime end) {
        // Only the upper limit needs checking: validateSlotAlignment already guarantees that end is after
        // start and both sit on :00/:30, so a booking can never be shorter than 30 minutes here.
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(Duration.ofMinutes(MAX_DURATION_MINUTES)) > 0) {
            throw new BusinessRuleException(
                    "INVALID_DURATION", "Reservation duration must be between 30 minutes and 4 hours");
        }
    }

    private static void validateHorizon(LocalDateTime start, LocalDateTime now) {
        if (start.isAfter(now.plusDays(MAX_ADVANCE_DAYS))) {
            throw new BusinessRuleException(
                    "BOOKING_TOO_FAR_AHEAD", "Reservations cannot be made more than 14 days in advance");
        }
    }

    private static void validateOpeningHours(LocalDateTime start, LocalDateTime end) {
        if (!start.toLocalDate().isEqual(end.toLocalDate())) {
            throw new BusinessRuleException(
                    "OUTSIDE_OPENING_HOURS", "Reservation start and end times must be on the same date");
        }
        DayOfWeek day = start.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            throw new BusinessRuleException(
                    "OUTSIDE_OPENING_HOURS", "Reservations are only permitted Monday through Friday");
        }
        if (start.toLocalTime().isBefore(OPEN_TIME) || end.toLocalTime().isAfter(CLOSE_TIME)) {
            throw new BusinessRuleException(
                    "OUTSIDE_OPENING_HOURS", "Reservations must be within opening hours (08:00 to 20:00)");
        }
    }
}

package com.campus.reservation.service;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ReservationResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing reservations and implementing the public {@link ReservationApi} facade.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService implements ReservationApi {

    private final ReservationRepository reservationRepository;
    private final CatalogApi catalogApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates a new reservation adhering to strict locking order and business rule validations.
     *
     * <p>Concurrency note: We acquire a pessimistic row lock on the asset in catalog FIRST before
     * checking overlaps, ensuring simultaneous requests cannot double-book the same asset slot.
     */
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = LocalDateTime.now(clock);

        validateReservationRequest(req, now);

        // 1. Lock asset row first (pessimistic lock prevents race conditions on overlapping slots)
        catalogApi.lockForBooking(req.assetId());

        // 2. Operational bookable check
        if (!catalogApi.isBookable(req.assetId())) {
            throw new ConflictException("ASSET_NOT_BOOKABLE", "Asset is not available for booking");
        }

        // 3. Overlap check against active reservations
        if (reservationRepository.existsOverlap(
                req.assetId(), req.startTime(), req.endTime(), ReservationStatus.ACTIVE_STATUSES, -1L)) {
            throw new ConflictException("RESERVATION_CONFLICT", "Requested slot overlaps with an existing reservation");
        }

        // 4. Active limit check for students (admins bypass this rule).
        // Known limitation: Two parallel requests from the same student for two different assets
        // lock different asset rows, so both could pass the < 2 check concurrently.
        if (!currentUser.isAdmin()) {
            if (reservationRepository.countByUserIdAndStatusIn(userId, ReservationStatus.ACTIVE_STATUSES) >= 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
            }
        }

        Reservation reservation = new Reservation(userId, req.assetId(), req.startTime(), req.endTime(), now);
        Reservation saved = reservationRepository.save(reservation);

        auditService.record(userId, "CREATE_RESERVATION", "Reservation", saved.getId(), "Asset " + req.assetId());

        return ReservationResponse.from(saved);
    }

    private void validateReservationRequest(CreateReservationRequest req, LocalDateTime now) {
        validateSlotRules(req.startTime(), req.endTime(), now);
    }

    /**
     * Shared validation of reservation slot rules (time range, future start, :00/:30 alignment,
     * 30m-4h duration, 14-day horizon, and Mon-Fri 08:00-20:00 opening hours).
     */
    public void validateSlotRules(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
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

    private void validateSlotAlignment(LocalDateTime start, LocalDateTime end) {
        if (start.getSecond() != 0
                || start.getNano() != 0
                || (start.getMinute() != 0 && start.getMinute() != 30)
                || end.getSecond() != 0
                || end.getNano() != 0
                || (end.getMinute() != 0 && end.getMinute() != 30)) {
            throw new BusinessRuleException(
                    "SLOT_NOT_ALIGNED",
                    "Reservation start and end times must be aligned to 0 or 30 minutes with zero seconds");
        }
    }

    private void validateDuration(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(Duration.ofMinutes(30)) < 0 || duration.compareTo(Duration.ofHours(4)) > 0) {
            throw new BusinessRuleException(
                    "INVALID_DURATION", "Reservation duration must be between 30 minutes and 4 hours");
        }
    }

    private void validateHorizon(LocalDateTime start, LocalDateTime now) {
        if (start.isAfter(now.plusDays(14))) {
            throw new BusinessRuleException(
                    "BOOKING_TOO_FAR_AHEAD", "Reservations cannot be made more than 14 days in advance");
        }
    }

    private void validateOpeningHours(LocalDateTime start, LocalDateTime end) {
        if (!start.toLocalDate().isEqual(end.toLocalDate())) {
            throw new BusinessRuleException(
                    "OUTSIDE_OPENING_HOURS", "Reservation start and end times must be on the same date");
        }
        DayOfWeek day = start.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            throw new BusinessRuleException(
                    "OUTSIDE_OPENING_HOURS", "Reservations are only permitted Monday through Friday");
        }
        LocalTime open = LocalTime.of(8, 0);
        LocalTime close = LocalTime.of(20, 0);
        if (start.toLocalTime().isBefore(open) || end.toLocalTime().isAfter(close)) {
            throw new BusinessRuleException(
                    "OUTSIDE_OPENING_HOURS", "Reservations must be within opening hours (08:00 to 20:00)");
        }
    }

    @Override
    public List<ReservationSummary> findUpcomingActiveByAsset(Long assetId, LocalDateTime from) {
        return List.of();
    }

    @Override
    public boolean isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId) {
        long ignore = ignoreReservationId == null ? -1L : ignoreReservationId;
        return !reservationRepository.existsOverlap(assetId, start, end, ReservationStatus.ACTIVE_STATUSES, ignore);
    }

    @Override
    @Transactional
    public void moveToAsset(Long reservationId, Long newAssetId, String reason) {
        // Full logic will be added with reassignment engine
    }

    @Override
    @Transactional
    public void forceCancel(Long reservationId, String reason) {
        // Full logic will be added with reassignment engine
    }

    @Override
    public Optional<ReservationSummary> findCurrentCheckout(Long assetId) {
        return Optional.empty();
    }
}

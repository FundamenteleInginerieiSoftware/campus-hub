package com.campus.reservation.service;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ReservationResponse;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    /**
     * Retrieves a paginated list of reservations for the current authenticated user.
     */
    public PageResponse<ReservationResponse> getMyReservations(ReservationStatus status, int page, int size) {
        Long userId = currentUser.id();
        Pageable pageable = PageRequest.of(page, size);

        Page<Reservation> reservationPage = status == null
                ? reservationRepository.findByUserIdOrderByStartTimeAsc(userId, pageable)
                : reservationRepository.findByUserIdAndStatusOrderByStartTimeAsc(userId, status, pageable);

        return PageResponse.from(reservationPage, ReservationResponse::from);
    }

    /**
     * Retrieves a reservation by ID ensuring ownership or admin privileges.
     *
     * <p>SECURITY NOTE: Throws 404 (NotFoundException) instead of 403 for unauthorized users
     * to prevent IDOR enumeration.
     */
    public ReservationResponse getById(Long id) {
        Reservation reservation = loadOwnedOrAdmin(id);
        return ReservationResponse.from(reservation);
    }

    /**
     * Cancels a CONFIRMED reservation.
     */
    @Transactional
    public ReservationResponse cancelReservation(Long id) {
        Reservation r = loadOwnedOrAdmin(id);
        LocalDateTime now = LocalDateTime.now(clock);

        if (!currentUser.isAdmin() && !now.isBefore(r.getStartTime())) {
            throw new ConflictException(
                    "CANCELLATION_NOT_ALLOWED", "Students can only cancel before the reservation start time");
        }

        if (r.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ConflictException("CANCELLATION_NOT_ALLOWED", "Only CONFIRMED reservations can be cancelled");
        }

        r.cancel(now, "Cancelled by user");
        auditService.record(currentUser.id(), "CANCEL_RESERVATION", "Reservation", r.getId(), "Cancelled by user");
        return ReservationResponse.from(r);
    }

    private Reservation loadOwnedOrAdmin(Long id) {
        Reservation reservation = reservationRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id));

        if (!reservation.getUserId().equals(currentUser.id()) && !currentUser.isAdmin()) {
            throw new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id);
        }

        return reservation;
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
        return reservationRepository.findUpcomingConfirmedByAsset(assetId, from).stream()
                .map(r -> new ReservationSummary(
                        r.getId(), r.getUserId(), r.getAssetId(), r.getStartTime(), r.getEndTime(), r.getStatus()))
                .toList();
    }

    @Override
    public boolean isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId) {
        long ignore = ignoreReservationId == null ? -1L : ignoreReservationId;
        return !reservationRepository.existsOverlap(assetId, start, end, ReservationStatus.ACTIVE_STATUSES, ignore);
    }

    // Bug fixed: without noRollbackFor, throwing SubstituteNoLongerFreeException through the Spring proxy
    // marked the caller's shared REQUIRES_NEW transaction rollback-only before ReassignmentService could catch it,
    // causing UnexpectedRollbackException on commit.
    @Override
    @Transactional(noRollbackFor = SubstituteNoLongerFreeException.class)
    public boolean moveToAsset(Long reservationId, Long newAssetId, String reason) {
        Reservation r = reservationRepository.findById(reservationId).orElse(null);
        // Rare race: if the booking was cancelled or checked out right after findUpcomingActiveByAsset,
        // return false so the caller does not log a false reassignment or send a misleading email.
        if (r == null || r.getStatus() != ReservationStatus.CONFIRMED) {
            return false;
        }

        catalogApi.lockForBooking(newAssetId);

        if (!catalogApi.isBookable(newAssetId)
                || reservationRepository.existsOverlap(
                        newAssetId, r.getStartTime(), r.getEndTime(), ReservationStatus.ACTIVE_STATUSES, r.getId())) {
            throw new SubstituteNoLongerFreeException("Substitute asset " + newAssetId + " is not free");
        }

        r.moveTo(newAssetId);
        auditService.record(null, "REASSIGN_RESERVATION", "Reservation", r.getId(), reason);
        return true;
    }

    @Override
    @Transactional
    public boolean forceCancel(Long reservationId, String reason) {
        Reservation r = reservationRepository.findById(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.CONFIRMED) {
            return false;
        }

        r.forceCancel(LocalDateTime.now(clock), reason);
        auditService.record(null, "FORCE_CANCEL_RESERVATION", "Reservation", r.getId(), reason);
        return true;
    }

    @Override
    public Optional<ReservationSummary> findCurrentCheckout(Long assetId) {
        return reservationRepository
                .findFirstByAssetIdAndStatusIn(
                        assetId, EnumSet.of(ReservationStatus.CHECKED_OUT, ReservationStatus.OVERDUE))
                .map(r -> new ReservationSummary(
                        r.getId(), r.getUserId(), r.getAssetId(), r.getStartTime(), r.getEndTime(), r.getStatus()));
    }

    /**
     * Admin action to check out equipment for a confirmed reservation at the lab desk.
     *
     * <p>Check-out window: only permitted between 15 minutes before slot start and 30 minutes after slot start.
     * Outside this window, throws {@code CHECKOUT_WINDOW_CLOSED}.
     */
    @Transactional
    public ReservationResponse checkOutReservation(Long id) {
        Reservation reservation = reservationRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id));

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowStart = reservation.getStartTime().minusMinutes(15);
        LocalDateTime windowEnd = reservation.getStartTime().plusMinutes(30);

        if (now.isBefore(windowStart) || now.isAfter(windowEnd)) {
            throw new BusinessRuleException(
                    "CHECKOUT_WINDOW_CLOSED",
                    "Check-out is only allowed between 15 minutes before slot start and 30 minutes after slot start");
        }

        reservation.checkOut(now);
        auditService.record(
                currentUser.id(), "CHECK_OUT_RESERVATION", "Reservation", reservation.getId(), "Checked out at " + now);
        return ReservationResponse.from(reservation);
    }

    /**
     * Admin action to return equipment for a checked-out or overdue reservation.
     */
    @Transactional
    public ReservationResponse returnReservation(Long id) {
        Reservation reservation = reservationRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id));

        LocalDateTime now = LocalDateTime.now(clock);
        reservation.returnItem(now);
        auditService.record(
                currentUser.id(), "RETURN_RESERVATION", "Reservation", reservation.getId(), "Returned at " + now);
        return ReservationResponse.from(reservation);
    }

    /**
     * Retrieves a paginated and dynamically filtered list of reservations for administrators.
     *
     * <p>Avoids JPQL null-check tricks by using Spring Data JPA {@link Specification}.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> getAdminReservations(
            ReservationStatus status, Long userId, Long assetId, LocalDate date, int page, int size) {
        Specification<Reservation> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (assetId != null) {
                predicates.add(cb.equal(root.get("assetId"), assetId));
            }
            if (date != null) {
                LocalDateTime dayStart = date.atStartOfDay();
                LocalDateTime nextDayStart = date.plusDays(1).atStartOfDay();
                predicates.add(cb.lessThan(root.get("startTime"), nextDayStart));
                predicates.add(cb.greaterThan(root.get("endTime"), dayStart));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"));
        Page<Reservation> reservationPage = reservationRepository.findAll(spec, pageable);
        return PageResponse.from(reservationPage, ReservationResponse::from);
    }

    /**
     * Marks a single confirmed reservation as {@code NO_SHOW} in its own transaction.
     * Called through the {@link ReservationService} proxy by {@link ReservationLifecycleService}
     * so an optimistic-lock conflict on one booking never rolls back the rest of the sweep, and
     * the no-show email is only sent after this transaction commits.
     */
    @Transactional
    public boolean markNoShow(Long reservationId) {
        Reservation r = reservationRepository.findById(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.CONFIRMED) {
            return false;
        }
        r.markNoShow();
        auditService.record(null, "AUTO_NO_SHOW", "Reservation", r.getId(), "Unclaimed 30m after startTime");
        return true;
    }

    /**
     * Marks a single checked-out reservation as {@code OVERDUE} in its own transaction.
     * Called through the {@link ReservationService} proxy by {@link ReservationLifecycleService}
     * so the overdue email is only sent after this transaction commits.
     */
    @Transactional
    public boolean markOverdue(Long reservationId) {
        Reservation r = reservationRepository.findById(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.CHECKED_OUT) {
            return false;
        }
        r.markOverdue();
        auditService.record(null, "MARK_OVERDUE", "Reservation", r.getId(), "Equipment not returned by endTime");
        return true;
    }
}

package com.campus.reservation.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.domain.ReservationTransfer;
import com.campus.reservation.domain.TransferStatus;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.ReservationTransferRepository;
import com.campus.reservation.web.AvailabilityResponse;
import com.campus.reservation.web.CreateHoldRequest;
import com.campus.reservation.web.CreateMultiAssetReservationRequest;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ExtendReservationRequest;
import com.campus.reservation.web.InitiateTransferRequest;
import com.campus.reservation.web.MultiAssetReservationResponse;
import com.campus.reservation.web.ReservationHoldResponse;
import com.campus.reservation.web.ReservationResponse;
import com.campus.reservation.web.ReservationSummaryResponse;
import com.campus.reservation.web.ReservationTransferResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Service managing reservations and implementing the public {@link ReservationApi} facade.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ReservationService implements ReservationApi {

    private final ReservationRepository reservationRepository;
    private final CatalogApi catalogApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final ReservationTransferRepository reservationTransferRepository;
    private final UserApi userApi;
    private final ReservationHoldRepository reservationHoldRepository;

    @Autowired
    public ReservationService(
            ReservationRepository reservationRepository,
            CatalogApi catalogApi,
            CurrentUser currentUser,
            AuditService auditService,
            NotificationService notificationService,
            Clock clock,
            Optional<ReservationTransferRepository> reservationTransferRepository,
            Optional<UserApi> userApi,
            Optional<ReservationHoldRepository> reservationHoldRepository) {
        this.reservationRepository = reservationRepository;
        this.catalogApi = catalogApi;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.clock = clock;
        this.reservationTransferRepository =
                reservationTransferRepository != null ? reservationTransferRepository.orElse(null) : null;
        this.userApi = userApi != null ? userApi.orElse(null) : null;
        this.reservationHoldRepository =
                reservationHoldRepository != null ? reservationHoldRepository.orElse(null) : null;
    }

    public ReservationService(
            ReservationRepository reservationRepository,
            CatalogApi catalogApi,
            CurrentUser currentUser,
            AuditService auditService,
            NotificationService notificationService,
            Clock clock,
            ReservationTransferRepository reservationTransferRepository,
            UserApi userApi,
            ReservationHoldRepository reservationHoldRepository) {
        this(
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                Optional.ofNullable(reservationTransferRepository),
                Optional.ofNullable(userApi),
                Optional.ofNullable(reservationHoldRepository));
    }

    public ReservationService(
            ReservationRepository reservationRepository,
            CatalogApi catalogApi,
            CurrentUser currentUser,
            AuditService auditService,
            NotificationService notificationService,
            Clock clock,
            ReservationTransferRepository reservationTransferRepository,
            UserApi userApi) {
        this(
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                Optional.ofNullable(reservationTransferRepository),
                Optional.ofNullable(userApi),
                Optional.empty());
    }

    public ReservationService(
            ReservationRepository reservationRepository,
            CatalogApi catalogApi,
            CurrentUser currentUser,
            AuditService auditService,
            NotificationService notificationService,
            Clock clock,
            ReservationHoldRepository reservationHoldRepository) {
        this(
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(reservationHoldRepository));
    }

    public ReservationService(
            ReservationRepository reservationRepository,
            CatalogApi catalogApi,
            CurrentUser currentUser,
            AuditService auditService,
            NotificationService notificationService,
            Clock clock,
            Optional<ReservationHoldRepository> reservationHoldRepository) {
        this(
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                Optional.empty(),
                Optional.empty(),
                reservationHoldRepository);
    }

    public ReservationService(
            ReservationRepository reservationRepository,
            CatalogApi catalogApi,
            CurrentUser currentUser,
            AuditService auditService,
            NotificationService notificationService,
            Clock clock) {
        this(
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * Creates a new reservation adhering to strict locking order and business rule validations.
     *
     * <p>Concurrency note: We acquire a pessimistic row lock on the asset in catalog FIRST before
     * checking overlaps, ensuring simultaneous requests cannot double-book the same asset slot.
     */
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

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

        // 4. Overlap check against active holds held by other students
        if (reservationHoldRepository != null
                && reservationHoldRepository.existsActiveOverlap(
                        req.assetId(), req.startTime(), req.endTime(), now, null, userId)) {
            throw new ConflictException("SLOT_HELD", "This time slot is temporarily held by another student");
        }

        // 5. Active limit check for students (admins bypass this rule).
        // Bug fixed (#169): two parallel requests from the same student for two different assets
        // lock different asset rows, so both could previously pass the < 2 check under READ COMMITTED.
        // Fix: acquire a transaction-scoped PostgreSQL advisory lock on userId before counting.
        // Lock order is always asset row -> user advisory lock (nothing locks an asset after the user lock),
        // so deadlocks cannot occur.
        if (!currentUser.isAdmin()) {
            reservationRepository.lockUserQuota(userId);
            if (reservationRepository.countByUserIdAndStatusIn(userId, ReservationStatus.ACTIVE_STATUSES) >= 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
            }
        }

        // Auto-confirm active hold owned by the current student if one exists for this slot
        if (reservationHoldRepository != null) {
            reservationHoldRepository.findByUserIdAndStatusAndExpiresAtAfter(userId, HoldStatus.ACTIVE, now).stream()
                    .filter(h -> h.getAssetId().equals(req.assetId())
                            && h.getStartTime().equals(req.startTime())
                            && h.getEndTime().equals(req.endTime()))
                    .findFirst()
                    .ifPresent(h -> {
                        h.confirm(now);
                        reservationHoldRepository.save(h);
                    });
        }

        Reservation reservation = new Reservation(userId, req.assetId(), req.startTime(), req.endTime(), now);
        Reservation saved = reservationRepository.save(reservation);

        auditService.record(userId, "CREATE_RESERVATION", "Reservation", saved.getId(), "Asset " + req.assetId());

        return ReservationResponse.from(saved);
    }

    /**
     * Simultaneously reserves multiple equipment items in a single atomic transaction.
     *
     * <p>Deadlock prevention & concurrency order: Asset rows are sorted in ascending ID order
     * before acquiring pessimistic row locks. This eliminates any possibility of circular lock waits
     * between simultaneous multi-asset booking transactions.
     *
     * <p>All-or-nothing guarantee: If any equipment item is unavailable (not bookable or overlapping booking),
     * the entire bundle is rejected with {@code 409} and no reservations are created.
     */
    @Transactional
    public MultiAssetReservationResponse createMultiAssetReservation(CreateMultiAssetReservationRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        validateSlotRules(req.startTime(), req.endTime(), now);

        boolean containsNull = false;
        if (req.assetIds() != null) {
            for (Long id : req.assetIds()) {
                if (id == null) {
                    containsNull = true;
                    break;
                }
            }
        }
        if (req.assetIds() == null
                || containsNull
                || req.assetIds().size() < 2
                || req.assetIds().size() > 5) {
            throw new BusinessRuleException(
                    "INVALID_BUNDLE_SIZE", "Multi-asset booking must include between 2 and 5 assets");
        }

        return executeBundleBooking(userId, req.assetIds(), req.startTime(), req.endTime(), now);
    }

    /**
     * In-process facade implementation for atomically booking a kit of 1 to 5 assets (all-or-nothing).
     * Used by the Project Assistant engine (#140) and other cross-module integrations.
     */
    @Override
    @Transactional
    public UUID bookBundle(Long userId, List<Long> assetIds, LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = now();
        validateSlotRules(start, end, now);

        boolean containsNull = false;
        if (assetIds != null) {
            for (Long id : assetIds) {
                if (id == null) {
                    containsNull = true;
                    break;
                }
            }
        }
        if (assetIds == null || assetIds.isEmpty() || assetIds.size() > 5 || containsNull) {
            throw new BusinessRuleException(
                    "INVALID_BUNDLE_SIZE", "Multi-asset booking must include between 1 and 5 assets");
        }

        return executeBundleBooking(userId, assetIds, start, end, now).bundleId();
    }

    private MultiAssetReservationResponse executeBundleBooking(
            Long userId, Collection<Long> assetIds, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        List<Long> sortedAssetIds = assetIds.stream().sorted().toList();

        // 1. Pessimistic lock on all assets in deterministic ID order (deadlock-free)
        for (Long assetId : sortedAssetIds) {
            catalogApi.lockForBooking(assetId);
        }

        // 2. Bookable check on all assets
        for (Long assetId : sortedAssetIds) {
            if (!catalogApi.isBookable(assetId)) {
                throw new ConflictException("ASSET_NOT_BOOKABLE", "Asset " + assetId + " is not available for booking");
            }
        }

        // 3. User quota check for students (entire multi-asset bundle counts as 1 lab session slot)
        boolean isAdmin = false;
        if (currentUser != null) {
            try {
                if (currentUser.id().equals(userId) && currentUser.isAdmin()) {
                    isAdmin = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (!isAdmin && userApi != null) {
            isAdmin = userApi.findActiveRole(userId).map(r -> r == Role.ADMIN).orElse(false);
        }

        if (!isAdmin) {
            reservationRepository.lockUserQuota(userId);
            long activeCount =
                    reservationRepository.countByUserIdAndStatusIn(userId, ReservationStatus.ACTIVE_STATUSES);
            if (activeCount + 1 > 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
            }
        }

        // 4. Overlap check on all assets (all-or-nothing atomicity against reservations and holds, #228 point 7)
        for (Long assetId : sortedAssetIds) {
            if (reservationRepository.existsOverlap(assetId, start, end, ReservationStatus.ACTIVE_STATUSES, -1L)) {
                throw new ConflictException(
                        "RESERVATION_CONFLICT",
                        "Asset " + assetId + " overlaps with an existing reservation in the requested window");
            }
            if (reservationHoldRepository != null
                    && reservationHoldRepository.existsActiveOverlap(assetId, start, end, now, null, userId)) {
                throw new ConflictException(
                        "SLOT_HELD", "Asset " + assetId + " is temporarily held by another student");
            }
        }

        // 5. Persist all reservations sharing a bundle UUID
        UUID bundleId = UUID.randomUUID();
        List<Reservation> savedList = new ArrayList<>();
        for (Long assetId : sortedAssetIds) {
            Reservation r = new Reservation(userId, assetId, start, end, now);
            r.assignBundle(bundleId);
            savedList.add(reservationRepository.save(r));
        }

        auditService.record(
                userId,
                "CREATE_BUNDLE_RESERVATION",
                "ReservationBundle",
                sortedAssetIds.get(0),
                "Bundle " + bundleId + " with " + savedList.size() + " assets: " + sortedAssetIds);

        notificationService.notifyUser(
                userId,
                "Reservation Bundle Confirmed",
                "Your reservation bundle #" + bundleId + " with " + savedList.size() + " assets was confirmed.");

        List<ReservationResponse> responses =
                savedList.stream().map(ReservationResponse::from).toList();
        return new MultiAssetReservationResponse(bundleId, start, end, responses.size(), responses);
    }

    /**
     * Retrieves all items in a multi-asset reservation bundle (owner or admin).
     */
    @Transactional(readOnly = true)
    public MultiAssetReservationResponse getBundle(UUID bundleId) {
        Long userId = currentUser.id();
        List<Reservation> items = reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId);
        if (items.isEmpty()) {
            throw new NotFoundException("BUNDLE_NOT_FOUND", "Reservation bundle not found");
        }

        // Per-item ownership check: non-admins can only see their own items (#228 point 1)
        if (!currentUser.isAdmin()) {
            items = items.stream().filter(r -> r.getUserId().equals(userId)).toList();
            if (items.isEmpty()) {
                throw new NotFoundException("BUNDLE_NOT_FOUND", "Reservation bundle not found");
            }
        }

        List<ReservationResponse> responses =
                items.stream().map(ReservationResponse::from).toList();
        Reservation first = items.get(0);
        return new MultiAssetReservationResponse(
                bundleId, first.getStartTime(), first.getEndTime(), responses.size(), responses);
    }

    /**
     * Cancels all active items in a multi-asset reservation bundle (owner or admin).
     */
    @Transactional
    public List<ReservationResponse> cancelBundle(UUID bundleId, String reason) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        List<Reservation> items = reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId);
        if (items.isEmpty()) {
            throw new NotFoundException("BUNDLE_NOT_FOUND", "Reservation bundle not found");
        }

        // Per-item ownership check: non-admins can only cancel items they own (#228 point 1)
        if (!currentUser.isAdmin()) {
            items = items.stream().filter(r -> r.getUserId().equals(userId)).toList();
            if (items.isEmpty()) {
                throw new NotFoundException("BUNDLE_NOT_FOUND", "Reservation bundle not found");
            }
        }

        Reservation first = items.get(0);
        // Student cancellation is only allowed before start time (#72)
        if (!currentUser.isAdmin() && !now.isBefore(first.getStartTime())) {
            throw new ConflictException(
                    "CANCELLATION_NOT_ALLOWED", "Reservations can only be cancelled before start time");
        }

        List<ReservationResponse> results = new ArrayList<>();
        Set<Long> ownerIds = new HashSet<>();
        for (Reservation r : items) {
            if (r.getStatus() == ReservationStatus.CONFIRMED) {
                if (currentUser.isAdmin()) {
                    r.cancel(now, reason != null ? reason : "Cancelled by admin");
                } else {
                    r.cancel(now, "Cancelled by user");
                }
                reservationRepository.save(r);
                cancelPendingTransferIfExists(
                        r.getId(),
                        now,
                        userId,
                        "Auto-cancelled due to bundle cancellation",
                        "The transfer offer for reservation #"
                                + r.getId()
                                + " was cancelled because the bundle was cancelled.");
                auditService.record(userId, "CANCEL_RESERVATION", "Reservation", r.getId(), "Bundle " + bundleId);
                ownerIds.add(r.getUserId());
            }
            results.add(ReservationResponse.from(r));
        }

        // Notify each owner of the cancelled items (fixes notifying admin on admin cancel, #228 point 12)
        for (Long ownerId : ownerIds) {
            notificationService.notifyUser(
                    ownerId,
                    "Reservation Bundle Cancelled",
                    "Your reservation bundle #" + bundleId + " was cancelled.");
        }

        return results;
    }

    /**
     * Retrieves a paginated list of reservations for the current authenticated user.
     */
    public PageResponse<ReservationResponse> getMyReservations(ReservationStatus status, int page, int size) {
        return getMyReservations(status != null ? List.of(status) : null, page, size);
    }

    /**
     * Retrieves a paginated list of reservations for the current authenticated user filtered by statuses.
     */
    public PageResponse<ReservationResponse> getMyReservations(List<ReservationStatus> statuses, int page, int size) {
        validatePagination(page, size);
        Long userId = currentUser.id();
        Pageable pageable = PageRequest.of(page, size);

        Page<Reservation> reservationPage;
        if (statuses == null || statuses.isEmpty()) {
            reservationPage = reservationRepository.findByUserIdOrderByStartTimeAsc(userId, pageable);
        } else if (statuses.size() == 1) {
            reservationPage = reservationRepository.findByUserIdAndStatusOrderByStartTimeAsc(
                    userId, statuses.getFirst(), pageable);
        } else {
            reservationPage =
                    reservationRepository.findByUserIdAndStatusInOrderByStartTimeAsc(userId, statuses, pageable);
        }

        return PageResponse.from(reservationPage, ReservationResponse::from);
    }

    /**
     * Retrieves aggregate counts for the current student's reservations (active, completed, reassigned).
     */
    public ReservationSummaryResponse getMySummary() {
        Long userId = currentUser.id();
        long active = reservationRepository.countByUserIdAndStatusIn(
                userId, List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_OUT, ReservationStatus.OVERDUE));
        long completed = reservationRepository.countByUserIdAndStatusIn(userId, List.of(ReservationStatus.RETURNED));
        long reassigned = reservationRepository.countByUserIdAndOriginalAssetIdIsNotNull(userId);
        return new ReservationSummaryResponse(active, completed, reassigned);
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
        LocalDateTime now = now();

        if (!currentUser.isAdmin() && !now.isBefore(r.getStartTime())) {
            throw new ConflictException(
                    "CANCELLATION_NOT_ALLOWED", "Students can only cancel before the reservation start time");
        }

        if (r.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ConflictException("CANCELLATION_NOT_ALLOWED", "Only CONFIRMED reservations can be cancelled");
        }

        // Bug fixed: previously, admin cancellations on another student's booking still recorded
        // "Cancelled by user" and never notified the student. Fix: distinguish admin-initiated
        // cancellations from self-cancellations and notify the reservation owner after commit.
        boolean cancelledByAdminForAnotherUser = !r.getUserId().equals(currentUser.id());
        String reason = cancelledByAdminForAnotherUser ? "Cancelled by administrator" : "Cancelled by user";
        r.cancel(now, reason);
        auditService.record(currentUser.id(), "CANCEL_RESERVATION", "Reservation", r.getId(), reason);

        // Auto-cancel any pending transfer offer to prevent orphaned transfers (#214, #228 point 6)
        cancelPendingTransferIfExists(
                id,
                now,
                currentUser.id(),
                "Auto-cancelled due to reservation cancellation",
                "The transfer offer for reservation #" + id + " was cancelled because the reservation was cancelled.");

        if (cancelledByAdminForAnotherUser) {
            Long ownerId = r.getUserId();
            Long reservationId = r.getId();
            Runnable notifyTask = () -> {
                try {
                    notificationService.notifyUser(
                            ownerId,
                            "Reservation Cancelled by Administrator",
                            "Your reservation #" + reservationId + " has been cancelled by an administrator.");
                } catch (Exception e) {
                    log.warn("Failed to notify user {} of admin cancellation: {}", ownerId, e.getMessage());
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        notifyTask.run();
                    }
                });
            } else {
                notifyTask.run();
            }
        }
        return ReservationResponse.from(r);
    }

    /**
     * Auto-cancels any pending transfer offer when a reservation becomes invalid
     * (cancelled, force-cancelled, moved on reassignment, no-show, bundle cancelled) (#228 point 6).
     *
     * @param actorId the ID of the user triggering the cancellation, or null for system events
     */
    private void cancelPendingTransferIfExists(
            Long reservationId, LocalDateTime now, Long actorId, String auditReason, String notificationMessage) {
        if (reservationTransferRepository != null) {
            reservationTransferRepository
                    .findByReservationIdAndStatus(reservationId, TransferStatus.PENDING)
                    .ifPresent(transfer -> {
                        transfer.cancel(now);
                        reservationTransferRepository.save(transfer);
                        auditService.record(
                                actorId, "TRANSFER_CANCELLED", "ReservationTransfer", transfer.getId(), auditReason);
                        notificationService.notifyUser(
                                transfer.getToUserId(), "Reservation Transfer Cancelled", notificationMessage);
                    });
        }
    }

    /**
     *
     * Extends the duration of an active reservation (CONFIRMED or CHECKED_OUT).
     *
     * <p>Concurrency & Atomicity: Locks the asset row first, verifies that the extended slot
     * [currentEndTime, newEndTime] has no overlapping reservations, and validates operating hours
     * and duration limits (max 4h total). If unavailable, the reservation remains unchanged.
     */
    @Transactional
    public ReservationResponse extendReservation(Long id, ExtendReservationRequest req) {
        Reservation reservation = loadOwnedOrAdmin(id);
        LocalDateTime now = now();

        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.CHECKED_OUT) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Only CONFIRMED or CHECKED_OUT reservations can be extended; current status is "
                            + reservation.getStatus());
        }

        LocalDateTime newEndTime = req.newEndTime();

        if (!now.isBefore(reservation.getEndTime())) {
            throw new ConflictException(
                    "RESERVATION_ALREADY_EXPIRED", "Cannot extend an expired or completed reservation");
        }

        // A CONFIRMED booking cannot be extended if its 30-minute check-out window has already passed
        if (reservation.getStatus() == ReservationStatus.CONFIRMED
                && now.isAfter(reservation.getStartTime().plusMinutes(30))) {
            throw new ConflictException(
                    "CHECKOUT_WINDOW_CLOSED", "Reservation cannot be extended because check-out deadline has passed");
        }

        if (!newEndTime.isAfter(reservation.getEndTime())) {
            throw new BusinessRuleException("INVALID_TIME_RANGE", "Extended end time must be after current end time");
        }

        validateExtensionRules(reservation.getStartTime(), newEndTime, now);

        // 1. Lock asset row first to serialize concurrent bookings or extensions on this asset
        catalogApi.lockForBooking(reservation.getAssetId());

        // Re-verify status under row lock in case of concurrent status transition
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.CHECKED_OUT) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Reservation status changed concurrently to " + reservation.getStatus());
        }

        // 2. Operational state check: asset must be bookable (not UNDER_MAINTENANCE or RETIRED)
        if (!catalogApi.isBookable(reservation.getAssetId())) {
            throw new ConflictException(
                    "ASSET_NOT_BOOKABLE",
                    "Asset " + reservation.getAssetId() + " is under maintenance or retired and cannot be extended");
        }

        // 3. Overlap check on the extension window [currentEndTime, newEndTime] against reservations and holds (#228
        // point 7)
        if (reservationRepository.existsOverlap(
                reservation.getAssetId(),
                reservation.getEndTime(),
                newEndTime,
                ReservationStatus.ACTIVE_STATUSES,
                reservation.getId())) {
            throw new ConflictException(
                    "EXTENSION_CONFLICT", "Cannot extend reservation: subsequent slot is already booked");
        }
        if (reservationHoldRepository != null
                && reservationHoldRepository.existsActiveOverlap(
                        reservation.getAssetId(),
                        reservation.getEndTime(),
                        newEndTime,
                        now,
                        null,
                        reservation.getUserId())) {
            throw new ConflictException("SLOT_HELD", "This time slot is temporarily held by another student");
        }

        reservation.extendEndTime(newEndTime);
        Reservation saved = reservationRepository.save(reservation);

        auditService.record(
                currentUser.id(),
                "EXTEND_RESERVATION",
                "Reservation",
                saved.getId(),
                "Extended to " + newEndTime + " on asset " + saved.getAssetId());

        notificationService.notifyUser(
                reservation.getUserId(),
                "Reservation Extended",
                "Your reservation #"
                        + saved.getId()
                        + " on asset "
                        + saved.getAssetId()
                        + " has been extended to "
                        + newEndTime
                        + ".");

        return ReservationResponse.from(saved);
    }

    /**
     * Initiates a transfer request offering a confirmed reservation to another student.
     */
    @Transactional
    public ReservationTransferResponse initiateTransfer(Long reservationId, InitiateTransferRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        Reservation reservation = loadOwnedOrAdmin(reservationId);

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Only CONFIRMED reservations can be transferred; current status is " + reservation.getStatus());
        }

        if (!now.isBefore(reservation.getStartTime())) {
            throw new ConflictException(
                    "TRANSFER_NOT_ALLOWED", "Cannot transfer a reservation that has already started or elapsed");
        }

        // Items in a multi-asset bundle cannot be transferred individually (#228 point 1)
        if (reservation.getBundleId() != null) {
            throw new ConflictException(
                    "BUNDLE_TRANSFER_NOT_ALLOWED", "Items part of a bundle cannot be transferred individually");
        }

        Long recipientId = req.recipientUserId();
        Long fromUserId = reservation.getUserId();
        if (recipientId.equals(fromUserId)) {
            throw new ConflictException("CANNOT_TRANSFER_TO_SELF", "Cannot transfer a reservation to yourself");
        }

        // Verify recipient exists, is active, and is a STUDENT (#228 point 2)
        if (userApi != null) {
            Optional<com.campus.user.api.Role> activeRole = userApi.findActiveRole(recipientId);
            if (activeRole.isEmpty() || activeRole.get() != com.campus.user.api.Role.STUDENT) {
                throw new NotFoundException(
                        "USER_NOT_FOUND", "Recipient student not found or inactive: " + recipientId);
            }
        }

        // Row lock serializes simultaneous transfer requests on the same reservation,
        // so concurrent requests cleanly receive 409 TRANSFER_ALREADY_PENDING instead of a DB unique constraint
        // violation (#228 point 3)
        catalogApi.lockForBooking(reservation.getAssetId());

        // Check if a transfer is already pending
        if (reservationTransferRepository
                .findByReservationIdAndStatus(reservationId, TransferStatus.PENDING)
                .isPresent()) {
            throw new ConflictException(
                    "TRANSFER_ALREADY_PENDING", "A transfer offer is already pending for this reservation");
        }

        // Check recipient quota
        if (reservationRepository.countByUserIdAndStatusIn(recipientId, ReservationStatus.ACTIVE_STATUSES) >= 2) {
            throw new ConflictException(
                    "RESERVATION_LIMIT_REACHED", "Recipient student already has 2 active reservations");
        }

        // fromUserId is always the reservation owner (fixes admin transfer fromUserId bug, #228 point 5)
        ReservationTransfer transfer = new ReservationTransfer(reservationId, fromUserId, recipientId, now);
        ReservationTransfer saved = reservationTransferRepository.save(transfer);

        auditService.record(
                userId,
                "TRANSFER_INITIATED",
                "ReservationTransfer",
                saved.getId(),
                "Offered reservation #" + reservationId + " to user #" + recipientId);

        notificationService.notifyUser(
                recipientId,
                "Reservation Transfer Offer",
                "User #" + fromUserId + " has offered to transfer reservation #" + reservationId + " to you.");

        return ReservationTransferResponse.from(saved);
    }

    /**
     * Retrieves all incoming pending transfer requests for the authenticated student.
     */
    @Transactional(readOnly = true)
    public List<ReservationTransferResponse> getMyPendingTransfers() {
        Long userId = currentUser.id();
        return reservationTransferRepository
                .findByToUserIdAndStatusOrderByRequestedAtDesc(userId, TransferStatus.PENDING)
                .stream()
                .map(ReservationTransferResponse::from)
                .toList();
    }

    /**
     * Accepts a pending transfer offer and atomically moves reservation ownership to the recipient.
     */
    @Transactional
    public ReservationTransferResponse acceptTransfer(Long transferId) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        ReservationTransfer transfer = reservationTransferRepository
                .findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found"));

        if (!transfer.getToUserId().equals(userId)) {
            throw new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found");
        }

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "INVALID_TRANSFER_STATUS_TRANSITION", "Cannot accept transfer in status " + transfer.getStatus());
        }

        // Verify recipient is still active
        if (userApi != null && userApi.findActiveRole(userId).isEmpty()) {
            throw new NotFoundException("USER_NOT_FOUND", "Recipient user not found or inactive: " + userId);
        }

        // Verify sender is still active
        if (userApi != null && userApi.findActiveRole(transfer.getFromUserId()).isEmpty()) {
            throw new ConflictException("TRANSFER_NOT_ALLOWED", "Sender account is no longer active");
        }

        Reservation reservation = reservationRepository
                .findById(transfer.getReservationId())
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found"));

        if (reservation.getStatus() != ReservationStatus.CONFIRMED || !now.isBefore(reservation.getStartTime())) {
            throw new ConflictException("TRANSFER_NOT_ALLOWED", "Reservation is no longer valid for transfer");
        }

        // Ensure reservation owner still matches transfer sender (prevents double transfer or stale ownership)
        if (!reservation.getUserId().equals(transfer.getFromUserId())) {
            throw new ConflictException("TRANSFER_NOT_ALLOWED", "Reservation owner no longer matches transfer sender");
        }

        // Acquire advisory lock on recipient quota (bypassed by admins)
        if (!currentUser.isAdmin()) {
            reservationRepository.lockUserQuota(userId);
            if (reservationRepository.countByUserIdAndStatusIn(userId, ReservationStatus.ACTIVE_STATUSES) >= 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
            }
        }

        transfer.accept(now);
        reservation.transferOwnership(userId);

        reservationTransferRepository.save(transfer);
        reservationRepository.save(reservation);

        auditService.record(
                userId,
                "TRANSFER_ACCEPTED",
                "ReservationTransfer",
                transfer.getId(),
                "Ownership of reservation #"
                        + reservation.getId()
                        + " transferred from user #"
                        + transfer.getFromUserId()
                        + " to #"
                        + userId);

        notificationService.notifyUser(
                transfer.getFromUserId(),
                "Reservation Transfer Accepted",
                "User #" + userId + " accepted your transfer of reservation #" + reservation.getId() + ".");

        return ReservationTransferResponse.from(transfer);
    }

    /**
     * Rejects an incoming pending reservation transfer request.
     */
    @Transactional
    public ReservationTransferResponse rejectTransfer(Long transferId) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        ReservationTransfer transfer = reservationTransferRepository
                .findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found"));

        if (!transfer.getToUserId().equals(userId) && !currentUser.isAdmin()) {
            throw new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found");
        }

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "INVALID_TRANSFER_STATUS_TRANSITION", "Cannot reject transfer in status " + transfer.getStatus());
        }

        transfer.reject(now);
        ReservationTransfer saved = reservationTransferRepository.save(transfer);

        auditService.record(
                userId,
                "TRANSFER_REJECTED",
                "ReservationTransfer",
                transfer.getId(),
                "Rejected by recipient user #" + userId);

        notificationService.notifyUser(
                transfer.getFromUserId(),
                "Reservation Transfer Rejected",
                "User #" + userId + " declined your transfer of reservation #" + transfer.getReservationId() + ".");

        return ReservationTransferResponse.from(saved);
    }

    /**
     * Cancels an outgoing pending reservation transfer request by the original sender.
     */
    @Transactional
    public ReservationTransferResponse cancelTransfer(Long transferId) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        ReservationTransfer transfer = reservationTransferRepository
                .findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found"));

        if (!transfer.getFromUserId().equals(userId) && !currentUser.isAdmin()) {
            throw new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found");
        }

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "INVALID_TRANSFER_STATUS_TRANSITION", "Cannot cancel transfer in status " + transfer.getStatus());
        }

        transfer.cancel(now);
        ReservationTransfer saved = reservationTransferRepository.save(transfer);

        auditService.record(
                userId,
                "TRANSFER_CANCELLED",
                "ReservationTransfer",
                transfer.getId(),
                "Cancelled by sender user #" + userId);

        notificationService.notifyUser(
                transfer.getToUserId(),
                "Reservation Transfer Cancelled",
                "User #" + userId + " cancelled the transfer offer for reservation #" + transfer.getReservationId()
                        + ".");

        return ReservationTransferResponse.from(saved);
    }

    /**
     * Evaluates equipment availability for a requested time slot.
     *
     * <p>If {@code assetId} is provided, evaluates whether that specific equipment is bookable and free.
     * If {@code categoryId} is provided, queries all operational assets in that category and excludes any
     * that have conflicting active bookings during the requested window.
     */
    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(
            Long assetId, Long categoryId, LocalDateTime start, LocalDateTime end) {
        if (assetId == null && categoryId == null) {
            throw new BusinessRuleException(
                    "MISSING_PARAMETER", "Either assetId or categoryId must be provided to check availability");
        }
        if (assetId != null && categoryId != null) {
            throw new BusinessRuleException("CONFLICTING_PARAMETERS", "Provide either assetId or categoryId, not both");
        }

        LocalDateTime now = now();
        validateSlotRules(start, end, now);

        if (assetId != null) {
            // Bug fixed (#155): getAsset(assetId) already returns the AssetSummary with its status;
            // checking asset.status() directly avoids a second identical SELECT via catalogApi.isBookable(assetId).
            AssetSummary asset = catalogApi.getAsset(assetId);

            if (asset.status() != AssetStatus.AVAILABLE) {
                return AvailabilityResponse.forAsset(assetId, start, end, false, "ASSET_NOT_BOOKABLE");
            }

            boolean reservationOverlap =
                    reservationRepository.existsOverlap(assetId, start, end, ReservationStatus.ACTIVE_STATUSES, -1L);
            if (reservationOverlap) {
                return AvailabilityResponse.forAsset(assetId, start, end, false, "RESERVATION_CONFLICT");
            }

            if (reservationHoldRepository != null) {
                Long currentUserId = null;
                try {
                    currentUserId = currentUser != null ? currentUser.id() : null;
                } catch (Exception ignored) {
                }
                if (reservationHoldRepository.existsActiveOverlap(assetId, start, end, now(), null, currentUserId)) {
                    return AvailabilityResponse.forAsset(assetId, start, end, false, "RESERVATION_CONFLICT");
                }
            }

            return AvailabilityResponse.forAsset(assetId, start, end, true, null);
        }

        // Category-wide availability evaluation (including active holds, #228 point 8)
        // Liviu's CatalogApi.findAvailableByCategory(categoryId, excludeAssetId): passing -1L means "exclude nothing"
        List<AssetSummary> candidateAssets = catalogApi.findAvailableByCategory(categoryId, -1L);
        Set<Long> busyAssetIds = new HashSet<>(findBusyAssetIds(start, end));

        List<Long> availableAssetIds = candidateAssets.stream()
                .map(AssetSummary::id)
                .filter(id -> !busyAssetIds.contains(id))
                .toList();

        return AvailabilityResponse.forCategory(categoryId, start, end, availableAssetIds);
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
        // Only the upper limit needs checking: validateSlotAlignment already guarantees that end is after
        // start and both sit on :00/:30, so a booking can never be shorter than 30 minutes here.
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(Duration.ofHours(4)) > 0) {
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

    private void validateExtensionRules(LocalDateTime start, LocalDateTime newEnd, LocalDateTime now) {
        validateSlotAlignment(start, newEnd);
        validateDuration(start, newEnd);
        validateHorizon(newEnd, now);
        validateOpeningHours(start, newEnd);
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

    public List<Long> findBusyAssetIds(LocalDateTime start, LocalDateTime end) {
        Set<Long> busy = new LinkedHashSet<>(
                reservationRepository.findBusyAssetIds(start, end, ReservationStatus.ACTIVE_STATUSES));
        if (reservationHoldRepository != null) {
            Long currentUserId = null;
            try {
                currentUserId = currentUser != null ? currentUser.id() : null;
            } catch (Exception ignored) {
            }
            busy.addAll(reservationHoldRepository.findHeldAssetIds(start, end, now(), currentUserId));
        }
        return new ArrayList<>(busy);
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
                || !isAssetFree(newAssetId, r.getStartTime(), r.getEndTime(), r.getId())) {
            throw new SubstituteNoLongerFreeException("Substitute asset " + newAssetId + " is not free");
        }

        r.moveTo(newAssetId);
        cancelPendingTransferIfExists(
                reservationId,
                now(),
                null,
                "Auto-cancelled due to equipment reassignment to asset #" + newAssetId,
                "The transfer offer for reservation #"
                        + reservationId
                        + " was cancelled due to equipment reassignment.");
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

        r.forceCancel(now(), reason);
        cancelPendingTransferIfExists(
                reservationId,
                now(),
                null,
                "Auto-cancelled due to equipment force cancellation: " + reason,
                "The transfer offer for reservation #"
                        + reservationId
                        + " was cancelled due to equipment force cancellation.");
        auditService.record(null, "FORCE_CANCEL_RESERVATION", "Reservation", r.getId(), reason);
        return true;
    }

    @Override
    public Optional<ReservationSummary> findCurrentCheckout(Long assetId) {
        return reservationRepository
                .findFirstByAssetIdAndStatusInOrderByStartTimeDesc(
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

        LocalDateTime now = now();
        LocalDateTime windowStart = reservation.getStartTime().minusMinutes(15);
        LocalDateTime windowEnd = reservation.getStartTime().plusMinutes(30);

        if (now.isBefore(windowStart) || now.isAfter(windowEnd)) {
            throw new BusinessRuleException(
                    "CHECKOUT_WINDOW_CLOSED",
                    "Check-out is only allowed between 15 minutes before slot start and 30 minutes after slot start");
        }

        // Fast fail if not in CONFIRMED state before acquiring row locks
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Cannot check out reservation in status " + reservation.getStatus() + "; expected CONFIRMED");
        }

        // Lock the asset row first to serialize simultaneous desk check-outs on the same physical equipment
        catalogApi.lockForBooking(reservation.getAssetId());

        // Bug fixed (#113): these checks must run BEFORE reservation.checkOut(now).
        // If checkOut() ran first, Hibernate auto-flush would write status=CHECKED_OUT to the DB
        // before findCurrentCheckout runs; with ORDER BY startTime DESC, the query would return
        // *this* reservation instead of an earlier OVERDUE one, masking the conflict.

        // 1. Check that asset is bookable (operational). Throws 409 ASSET_NOT_BOOKABLE if marked
        // under maintenance or retired between booking and check-out.
        if (!catalogApi.isBookable(reservation.getAssetId())) {
            throw new ConflictException("ASSET_NOT_BOOKABLE", "Asset is not available for check-out");
        }

        // 2. Prevent check-out when the asset is still held by another reservation (CHECKED_OUT or OVERDUE).
        // Prevents double check-outs when an earlier student hasn't returned equipment yet.
        Optional<ReservationSummary> currentHolder = findCurrentCheckout(reservation.getAssetId());
        if (currentHolder.isPresent() && !currentHolder.get().id().equals(reservation.getId())) {
            throw new ConflictException(
                    "RESERVATION_CONFLICT",
                    "Asset is currently checked out or overdue under reservation #"
                            + currentHolder.get().id());
        }

        reservation.checkOut(now);

        // Auto-cancel any pending transfer offer on check-out so it doesn't remain orphaned (#228 point 6)
        cancelPendingTransferIfExists(
                reservation.getId(),
                now,
                currentUser != null ? currentUser.id() : null,
                "Auto-cancelled due to check-out",
                "The reservation offered to you (#" + reservation.getId() + ") was checked out by the owner.");

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

        LocalDateTime now = now();
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
        return getAdminReservations(status, userId, assetId, date, page, size, "desc");
    }

    /**
     * Retrieves a paginated and dynamically filtered list of reservations for administrators
     * with configurable sort direction on {@code startTime} (and secondary tie-breaker {@code id}).
     *
     * <p>Avoids JPQL null-check tricks by using Spring Data JPA {@link Specification}.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> getAdminReservations(
            ReservationStatus status, Long userId, Long assetId, LocalDate date, int page, int size, String sortDir) {
        validatePagination(page, size);
        Sort.Direction direction;
        if (sortDir == null || sortDir.equalsIgnoreCase("desc")) {
            direction = Sort.Direction.DESC;
        } else if (sortDir.equalsIgnoreCase("asc")) {
            direction = Sort.Direction.ASC;
        } else {
            throw new InvalidParameterException("sortDir must be 'asc' or 'desc'");
        }
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

        Pageable pageable =
                PageRequest.of(page, size, Sort.by(direction, "startTime").and(Sort.by(direction, "id")));
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
        cancelPendingTransferIfExists(
                reservationId,
                now(),
                null,
                "Auto-cancelled due to no-show",
                "The transfer offer for reservation #" + reservationId + " was cancelled due to no-show.");
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

    // Bug fixed (#150): prevents integer overflow when Hibernate casts pageable.getOffset() to int.
    private static void validatePagination(int page, int size) {
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

    // Bug fixed (#123): whole seconds, like the contract format (2026-11-03T14:00:00), so POST and GET
    // return consistent values without subsecond drift between API responses and PostgreSQL storage.
    // Note: because boundary comparisons (e.g. past check) use this truncated now, a booking starting at
    // 10:00:00 requested at 10:00:00.9 is accepted within the same second rather than being flagged as BOOKING_IN_PAST.
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Temporarily holds a lab asset time slot for 1 minute.
     *
     * <p>Concurrency note: We acquire a pessimistic row lock on the asset in catalog FIRST before
     * checking overlaps, ensuring simultaneous requests cannot double-hold the same asset slot.
     */
    @Transactional
    public ReservationHoldResponse createHold(CreateHoldRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        validateSlotRules(req.startTime(), req.endTime(), now);

        if (reservationHoldRepository == null) {
            throw new IllegalStateException("ReservationHoldRepository is not available");
        }

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

        // 4. Overlap check against active holds from other students
        if (reservationHoldRepository.existsActiveOverlap(
                req.assetId(), req.startTime(), req.endTime(), now, null, userId)) {
            throw new ConflictException("SLOT_HELD", "This time slot is temporarily held by another student");
        }

        // 5. Anti-hoarding & active limit checks for students
        if (!currentUser.isAdmin()) {
            reservationRepository.lockUserQuota(userId);

            // A student can hold at most 1 slot at a time
            long activeHolds =
                    reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(userId, HoldStatus.ACTIVE, now);
            if (activeHolds >= 1) {
                throw new ConflictException(
                        "ACTIVE_HOLD_LIMIT_REACHED",
                        "You already have an active reservation hold. Please confirm or release it before holding another slot.");
            }

            // Active reservations + active hold must not exceed quota (2)
            long activeReservations =
                    reservationRepository.countByUserIdAndStatusIn(userId, ReservationStatus.ACTIVE_STATUSES);
            if (activeReservations >= 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot exceed active reservation quota (max 2)");
            }
        }

        // Temporary hold duration: 1 minute per requirement
        LocalDateTime expiresAt = now.plusMinutes(1);
        ReservationHold hold =
                new ReservationHold(req.assetId(), userId, req.startTime(), req.endTime(), expiresAt, now);
        ReservationHold saved = reservationHoldRepository.save(hold);

        auditService.record(
                userId,
                "HOLD_ACQUIRED",
                "ReservationHold",
                saved.getId(),
                "Held asset #" + req.assetId() + " until " + expiresAt);

        return ReservationHoldResponse.from(saved, now);
    }

    /**
     * Confirms an active reservation hold and converts it into a confirmed reservation.
     */
    @Transactional
    public ReservationResponse confirmHold(Long holdId) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        if (reservationHoldRepository == null) {
            throw new IllegalStateException("ReservationHoldRepository is not available");
        }

        // Lock hold row to prevent concurrent confirmation attempts
        ReservationHold hold = reservationHoldRepository
                .findByIdForUpdate(holdId)
                .orElseThrow(() -> new NotFoundException("HOLD_NOT_FOUND", "Reservation hold not found"));

        if (!hold.getUserId().equals(userId) && !currentUser.isAdmin()) {
            throw new NotFoundException("HOLD_NOT_FOUND", "Reservation hold not found");
        }

        // Validates hold status and checks if expired
        hold.confirm(now);

        // Re-validate slot rules at confirmation time to prevent past-start confirmations (#228 point 9)
        validateSlotRules(hold.getStartTime(), hold.getEndTime(), now);

        // Lock asset row
        catalogApi.lockForBooking(hold.getAssetId());

        if (!catalogApi.isBookable(hold.getAssetId())) {
            throw new ConflictException("ASSET_NOT_BOOKABLE", "Asset is not available for booking");
        }

        if (reservationRepository.existsOverlap(
                hold.getAssetId(), hold.getStartTime(), hold.getEndTime(), ReservationStatus.ACTIVE_STATUSES, -1L)) {
            throw new ConflictException("RESERVATION_CONFLICT", "Requested slot overlaps with an existing reservation");
        }

        Long studentId = hold.getUserId();
        // Enforce quota whenever the hold owner is a student, including when confirmed by an admin (#228 point 9)
        boolean holdOwnerIsStudent = userApi != null
                ? userApi.findActiveRole(studentId)
                        .map(r -> r == com.campus.user.api.Role.STUDENT)
                        .orElse(true)
                : !studentId.equals(userId) || !currentUser.isAdmin();
        if (holdOwnerIsStudent) {
            reservationRepository.lockUserQuota(studentId);
            if (reservationRepository.countByUserIdAndStatusIn(studentId, ReservationStatus.ACTIVE_STATUSES) >= 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
            }
        }

        reservationHoldRepository.save(hold);

        Reservation reservation =
                new Reservation(studentId, hold.getAssetId(), hold.getStartTime(), hold.getEndTime(), now);
        Reservation savedReservation = reservationRepository.save(reservation);

        auditService.record(
                userId,
                "CREATE_RESERVATION",
                "Reservation",
                savedReservation.getId(),
                "Confirmed from hold #" + hold.getId());

        notificationService.notifyUser(
                studentId, "Reservation Confirmed", "Your reservation #" + savedReservation.getId() + " is confirmed.");

        return ReservationResponse.from(savedReservation);
    }

    /**
     * Releases an active hold early, freeing the slot for other students.
     */
    @Transactional
    public ReservationHoldResponse releaseHold(Long holdId) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        if (reservationHoldRepository == null) {
            throw new IllegalStateException("ReservationHoldRepository is not available");
        }

        ReservationHold hold = reservationHoldRepository
                .findByIdForUpdate(holdId)
                .orElseThrow(() -> new NotFoundException("HOLD_NOT_FOUND", "Reservation hold not found"));

        if (!hold.getUserId().equals(userId) && !currentUser.isAdmin()) {
            throw new NotFoundException("HOLD_NOT_FOUND", "Reservation hold not found");
        }

        hold.release(now);
        ReservationHold saved = reservationHoldRepository.save(hold);

        auditService.record(userId, "HOLD_RELEASED", "ReservationHold", saved.getId(), "Released by user #" + userId);

        return ReservationHoldResponse.from(saved, now);
    }

    /**
     * Lists current active unexpired reservation holds for the authenticated user.
     */
    public List<ReservationHoldResponse> getMyActiveHolds() {
        if (reservationHoldRepository == null) {
            return List.of();
        }
        Long userId = currentUser.id();
        LocalDateTime now = now();
        return reservationHoldRepository.findByUserIdAndStatusAndExpiresAtAfter(userId, HoldStatus.ACTIVE, now).stream()
                .map(h -> ReservationHoldResponse.from(h, now))
                .toList();
    }

    /**
     * Sweeps expired reservation holds in status ACTIVE where expiresAt <= now.
     */
    @Transactional
    public int sweepExpiredHolds(LocalDateTime now) {
        if (reservationHoldRepository == null) {
            return 0;
        }
        int count = reservationHoldRepository.expireOldHolds(now);
        if (count > 0) {
            log.info("Expired {} abandoned reservation holds", count);
        }
        return count;
    }
}

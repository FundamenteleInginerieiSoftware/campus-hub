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
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.AvailabilityResponse;
import com.campus.reservation.web.CreateMultiAssetReservationRequest;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ExtendReservationRequest;
import com.campus.reservation.web.MultiAssetReservationResponse;
import com.campus.reservation.web.ReservationResponse;
import com.campus.reservation.web.ReservationSummaryResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService implements ReservationApi {

    static final long NO_RESERVATION_ID = -1L;

    private final ReservationRepository reservationRepository;
    private final CatalogApi catalogApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final TransferService transferService;
    private final UserApi userApi;
    private final ReservationHoldRepository reservationHoldRepository;

    /**
     * Creates a new reservation adhering to the global lock DAG ({@code Quota -> Asset -> Hold})
     * and business rule validations (#362 §1.1).
     */
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = now();

        ReservationSlotValidator.validateSlotRules(req.startTime(), req.endTime(), now);

        // Bug fixed (#362 §1.1, §3): acquire lockUserQuota FIRST (before Asset) for ALL callers, including
        // admins, so every path follows the strict global DAG Quota -> Reservation -> Asset -> Transfer -> Hold
        // and disabled admins cannot race past the active-account check.
        reservationRepository.lockUserQuota(userId);
        Role activeRole = ReservationServiceSupport.requireActiveUserAfterLock(userApi, userId);

        ReservationSlotValidator.lockAndValidateSlotFree(
                catalogApi,
                reservationRepository,
                reservationHoldRepository,
                req.assetId(),
                req.startTime(),
                req.endTime(),
                now,
                null,
                userId);

        // Active limit check for students (admins bypass the >= 2 count cap only).
        if (activeRole != Role.ADMIN
                && reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                                userId, ReservationStatus.ACTIVE_STATUSES)
                        >= 2) {
            throw new ConflictException(
                    "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
        }

        ReservationServiceSupport.confirmOwnedHoldsForSlots(
                reservationHoldRepository, userId, List.of(req.assetId()), req.startTime(), req.endTime(), now);

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

        ReservationSlotValidator.validateSlotRules(req.startTime(), req.endTime(), now);
        validateBundleAssetIds(req.assetIds(), 2, 5);

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
        ReservationSlotValidator.validateSlotRules(start, end, now);
        validateBundleAssetIds(assetIds, 1, 5);

        return executeBundleBooking(userId, assetIds, start, end, now).bundleId();
    }

    private void validateBundleAssetIds(Collection<Long> assetIds, int minSize, int maxSize) {
        if (assetIds == null
                || assetIds.size() < minSize
                || assetIds.size() > maxSize
                || assetIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessRuleException(
                    "INVALID_BUNDLE_SIZE",
                    "Multi-asset booking must include between " + minSize + " and " + maxSize + " assets");
        }
        // Bug fixed (#362 §3): reject duplicate asset IDs (possible via bookBundle(List<Long>)) before
        // locking/persisting so a kit never inserts two overlapping reservations on the same asset.
        if (Set.copyOf(assetIds).size() != assetIds.size()) {
            throw new BusinessRuleException(
                    "DUPLICATE_ASSET_IN_BUNDLE", "Multi-asset booking cannot contain duplicate asset IDs");
        }
    }

    private MultiAssetReservationResponse executeBundleBooking(
            Long userId, Collection<Long> assetIds, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        List<Long> sortedAssetIds = assetIds.stream().sorted().toList();

        // 1. Acquire per-user quota lock FIRST (Quota -> Asset -> Hold, #362 §1.1) and verify the account is
        // still active for ALL users including admins (#362 §3).
        reservationRepository.lockUserQuota(userId);
        Role role = ReservationServiceSupport.requireActiveUserAfterLock(userApi, userId);

        // 2. Pessimistic lock on all assets in deterministic ID order (deadlock-free)
        for (Long assetId : sortedAssetIds) {
            catalogApi.lockForBooking(assetId);
        }

        // 3. Bookable check on all assets
        for (Long assetId : sortedAssetIds) {
            ReservationSlotValidator.ensureBookable(
                    catalogApi, assetId, "Asset " + assetId + " is not available for booking");
        }

        // 4. User quota check for students (entire multi-asset bundle counts as 1 lab session slot).
        if (role != Role.ADMIN) {
            long activeCount = reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                    userId, ReservationStatus.ACTIVE_STATUSES);
            if (activeCount + 1 > 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
            }
        }

        // 5. Overlap check on all assets (all-or-nothing atomicity against reservations and holds)
        for (Long assetId : sortedAssetIds) {
            ReservationSlotValidator.ensureNoSlotConflict(
                    reservationRepository,
                    reservationHoldRepository,
                    assetId,
                    start,
                    end,
                    now,
                    null,
                    userId,
                    "Asset " + assetId + " overlaps with an existing reservation in the requested window",
                    "Asset " + assetId + " is temporarily held by another student");
        }

        // 6. Persist all reservations sharing a bundle UUID
        UUID bundleId = UUID.randomUUID();
        List<Reservation> savedList = new ArrayList<>();
        for (Long assetId : sortedAssetIds) {
            Reservation r = new Reservation(userId, assetId, start, end, now);
            r.assignBundle(bundleId);
            savedList.add(reservationRepository.save(r));
        }

        // Confirm any overlapping active holds the student already placed on these slots (locked FOR UPDATE).
        ReservationServiceSupport.confirmOwnedHoldsForSlots(
                reservationHoldRepository, userId, sortedAssetIds, start, end, now);

        auditService.record(
                userId,
                "CREATE_BUNDLE_RESERVATION",
                "ReservationBundle",
                sortedAssetIds.get(0),
                "Bundle " + bundleId + " with " + savedList.size() + " assets: " + sortedAssetIds);

        ReservationServiceSupport.notifyUserAfterCommit(
                notificationService,
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
    public MultiAssetReservationResponse getBundle(UUID bundleId) {
        List<Reservation> items = loadOwnedOrAdminBundle(bundleId, false);
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
        List<Reservation> items = loadOwnedOrAdminBundle(bundleId, true);

        Reservation first = items.get(0);
        // Student cancellation is only allowed before start time (#72)
        if (!currentUser.isAdmin() && !now.isBefore(first.getStartTime())) {
            throw new ConflictException(
                    "CANCELLATION_NOT_ALLOWED", "Reservations can only be cancelled before start time");
        }

        List<ReservationResponse> results = new ArrayList<>();
        Set<Long> ownerIds = new HashSet<>();
        int cancelledCount = 0;
        for (Reservation r : items) {
            if (r.getStatus() == ReservationStatus.CONFIRMED) {
                if (currentUser.isAdmin()) {
                    r.cancel(now, reason != null ? reason : "Cancelled by admin");
                } else {
                    r.cancel(now, "Cancelled by user");
                }
                reservationRepository.save(r);
                transferService.cancelPendingTransferIfExists(
                        r.getId(),
                        now,
                        userId,
                        "Auto-cancelled due to bundle cancellation",
                        "The transfer offer for reservation #"
                                + r.getId()
                                + " was cancelled because the bundle was cancelled.");
                auditService.record(userId, "CANCEL_RESERVATION", "Reservation", r.getId(), "Bundle " + bundleId);
                ownerIds.add(r.getUserId());
                cancelledCount++;
            }
            results.add(ReservationResponse.from(r));
        }

        // Bug fixed (#362 §4): do not return 200 OK when 0 items were CONFIRMED (e.g. already cancelled or checked
        // out).
        if (cancelledCount == 0) {
            throw new ConflictException(
                    "CANCELLATION_NOT_ALLOWED", "No CONFIRMED reservations remain in this bundle to cancel");
        }

        // Notify each owner of the cancelled items (fixes notifying admin on admin cancel, #228 point 12)
        for (Long ownerId : ownerIds) {
            ReservationServiceSupport.notifyUserAfterCommit(
                    notificationService,
                    ownerId,
                    "Reservation Bundle Cancelled",
                    "Your reservation bundle #" + bundleId + " was cancelled.");
        }

        return results;
    }

    private List<Reservation> loadOwnedOrAdminBundle(UUID bundleId, boolean forUpdate) {
        Long userId = currentUser.id();
        boolean isAdmin = currentUser.isAdmin();
        List<Reservation> items;
        if (forUpdate) {
            items = isAdmin
                    ? reservationRepository.findByBundleIdForUpdate(bundleId)
                    : reservationRepository.findByBundleIdAndUserIdForUpdate(bundleId, userId);
        } else {
            items = reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId);
            // Per-item ownership check: non-admins can only access their own items (#228 point 1)
            if (!isAdmin) {
                items = items.stream().filter(r -> r.getUserId().equals(userId)).toList();
            }
        }
        if (items.isEmpty()) {
            throw new NotFoundException("BUNDLE_NOT_FOUND", "Reservation bundle not found");
        }
        return items;
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
        ReservationServiceSupport.validatePagination(page, size);
        Long userId = currentUser.id();
        Pageable pageable = PageRequest.of(page, size);

        Page<Reservation> reservationPage;
        if (statuses == null || statuses.isEmpty()) {
            reservationPage = reservationRepository.findByUserIdOrderByStartTimeAscIdAsc(userId, pageable);
        } else if (statuses.size() == 1) {
            reservationPage = reservationRepository.findByUserIdAndStatusOrderByStartTimeAscIdAsc(
                    userId, statuses.getFirst(), pageable);
        } else {
            reservationPage =
                    reservationRepository.findByUserIdAndStatusInOrderByStartTimeAscIdAsc(userId, statuses, pageable);
        }

        return PageResponse.from(reservationPage, ReservationResponse::from);
    }

    /**
     * Retrieves aggregate counts for the current student's reservations (active, completed, reassigned).
     */
    public ReservationSummaryResponse getMySummary() {
        Long userId = currentUser.id();
        long active = reservationRepository.countBookingSessionsByUserIdAndStatusIn(
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
        return ReservationResponse.from(
                ReservationServiceSupport.loadOwnedOrAdmin(reservationRepository, currentUser, id));
    }

    /**
     * Cancels a CONFIRMED reservation.
     */
    @Transactional
    public ReservationResponse cancelReservation(Long id) {
        // Lock the reservation row FOR UPDATE so a concurrent rejectTransfer/cancelTransfer waits instead
        // of causing a STALE_VERSION error on the student's cancel (#329 follow-up).
        Reservation r = ReservationServiceSupport.loadOwnedOrAdminForUpdate(reservationRepository, currentUser, id);
        LocalDateTime now = now();

        // Check status before timing so non-CONFIRMED bookings get the status error rather than a timing error.
        if (r.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ConflictException("CANCELLATION_NOT_ALLOWED", "Only CONFIRMED reservations can be cancelled");
        }

        if (!currentUser.isAdmin() && !now.isBefore(r.getStartTime())) {
            throw new ConflictException(
                    "CANCELLATION_NOT_ALLOWED", "Students can only cancel before the reservation start time");
        }

        // Distinguish admin cancellations on another student's booking from self-cancellations so the audit
        // log records the real actor reason and the owner is notified after commit.
        boolean cancelledByAdminForAnotherUser = !r.getUserId().equals(currentUser.id());
        String reason = cancelledByAdminForAnotherUser ? "Cancelled by administrator" : "Cancelled by user";
        r.cancel(now, reason);
        auditService.record(currentUser.id(), "CANCEL_RESERVATION", "Reservation", r.getId(), reason);

        // Auto-cancel any pending transfer offer to prevent orphaned transfers (#214, #228 point 6)
        transferService.cancelPendingTransferIfExists(
                id,
                now,
                currentUser.id(),
                "Auto-cancelled due to reservation cancellation",
                "The transfer offer for reservation #" + id + " was cancelled because the reservation was cancelled.");

        if (cancelledByAdminForAnotherUser) {
            ReservationServiceSupport.notifyUserAfterCommit(
                    notificationService,
                    r.getUserId(),
                    "Reservation Cancelled by Administrator",
                    "Your reservation #" + r.getId() + " has been cancelled by an administrator.");
        }
        return ReservationResponse.from(r);
    }

    /**
     * Extends the duration of an active reservation (CONFIRMED or CHECKED_OUT).
     *
     * <p>Follows the global lock DAG ({@code Quota -> Reservation -> Asset -> Transfer}, #362 §1.1 & §3):
     * locks caller quota and verifies the account is active before locking the reservation and asset rows.
     */
    @Transactional
    public ReservationResponse extendReservation(Long id, ExtendReservationRequest req) {
        Long callerId = currentUser.id();
        reservationRepository.lockUserQuota(callerId);
        // Use fresh DB role (not stale JWT claim) so an admin demoted mid-flight cannot extend another user's booking.
        Role callerRole = ReservationServiceSupport.requireActiveUserAfterLock(userApi, callerId);

        Reservation reservation = ReservationServiceSupport.loadOwnedOrAdminForUpdate(
                reservationRepository, callerId, callerRole == Role.ADMIN, id);
        LocalDateTime now = now();

        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.CHECKED_OUT) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Only CONFIRMED or CHECKED_OUT reservations can be extended; current status is "
                            + reservation.getStatus());
        }

        // Items in a multi-asset bundle cannot be extended individually (#240 point 5)
        if (reservation.getBundleId() != null) {
            throw new ConflictException(
                    "BUNDLE_EXTEND_NOT_ALLOWED", "Individual items in a bundle booking cannot be extended");
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

        ReservationSlotValidator.validateExtensionRules(reservation.getStartTime(), newEndTime, now);

        // 1. Lock asset row to serialize concurrent bookings or extensions on this asset
        catalogApi.lockForBooking(reservation.getAssetId());

        // Re-verify status under row lock in case of concurrent status transition
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.CHECKED_OUT) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Reservation status changed concurrently to " + reservation.getStatus());
        }

        // 2. Operational state check: asset must be bookable (not UNDER_MAINTENANCE or RETIRED)
        ReservationSlotValidator.ensureBookable(
                catalogApi,
                reservation.getAssetId(),
                "Asset " + reservation.getAssetId() + " is under maintenance or retired and cannot be extended");

        // 3. Overlap check on the extension window [currentEndTime, newEndTime] against reservations and holds
        if (reservationRepository.existsOverlap(
                reservation.getAssetId(), reservation.getEndTime(), newEndTime, reservation.getId())) {
            throw new ConflictException(
                    "EXTENSION_CONFLICT", "Cannot extend reservation: subsequent slot is already booked");
        }
        ReservationSlotValidator.ensureNoActiveHoldOverlap(
                reservationHoldRepository,
                reservation.getAssetId(),
                reservation.getEndTime(),
                newEndTime,
                now,
                null,
                reservation.getUserId(),
                "This time slot is temporarily held by another student");

        reservation.extendEndTime(newEndTime);
        Reservation saved = reservationRepository.save(reservation);

        // Auto-cancel any pending transfer offer when reservation duration is extended (#240 point 6)
        transferService.cancelPendingTransferIfExists(
                reservation.getId(),
                now,
                currentUser.id(),
                "Auto-cancelled due to reservation duration extension",
                "The transfer offer for reservation #"
                        + reservation.getId()
                        + " was cancelled because the reservation duration was extended.");

        auditService.record(
                currentUser.id(),
                "EXTEND_RESERVATION",
                "Reservation",
                saved.getId(),
                "Extended to " + newEndTime + " on asset " + saved.getAssetId());

        ReservationServiceSupport.notifyUserAfterCommit(
                notificationService,
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
     * Evaluates equipment availability for a requested time slot.
     *
     * <p>If {@code assetId} is provided, evaluates whether that specific equipment is bookable and free.
     * If {@code categoryId} is provided, queries all operational assets in that category and excludes any
     * that have conflicting active bookings during the requested window.
     */
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
        ReservationSlotValidator.validateSlotRules(start, end, now);

        if (assetId != null) {
            // Reuse asset.status() from getAsset(assetId) to avoid a second SELECT via catalogApi.isBookable().
            AssetSummary asset = catalogApi.getAsset(assetId);
            // Match Liviu's GET /assets/{id} (#278) and NextFreeSlotsService (#353): hide RETIRED assets
            // from students with 404 ASSET_NOT_FOUND so non-admins cannot probe retired asset IDs (#362).
            if (asset.status() == AssetStatus.RETIRED && !currentUser.isAdmin()) {
                throw new NotFoundException("ASSET_NOT_FOUND", "Asset " + assetId + " was not found");
            }
            String conflictReason = null;
            if (asset.status() != AssetStatus.AVAILABLE) {
                conflictReason = "ASSET_NOT_BOOKABLE";
            } else if (reservationRepository.existsOverlap(assetId, start, end, NO_RESERVATION_ID)
                    || reservationHoldRepository.existsActiveOverlap(
                            assetId,
                            start,
                            end,
                            now,
                            null,
                            ReservationServiceSupport.currentUserIdOrNull(currentUser))) {
                conflictReason = "RESERVATION_CONFLICT";
            }
            if (conflictReason != null) {
                return AvailabilityResponse.forAsset(
                        assetId, start, end, false, conflictReason, findFreeSameModelSubstitutes(asset, start, end));
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

    // Mirrors SameModelStrategy's candidate ordering (same lab room first, then lowest ID)
    // so students who hit a slot conflict see the closest free identical unit right away.
    private List<Long> findFreeSameModelSubstitutes(AssetSummary asset, LocalDateTime start, LocalDateTime end) {
        if (asset.model() == null || asset.model().isBlank()) {
            return List.of();
        }
        List<AssetSummary> candidates = catalogApi.findAvailableByModel(asset.model(), asset.id());
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> busyIds = new HashSet<>(findBusyAssetIds(start, end));
        return candidates.stream()
                .filter(candidate -> !busyIds.contains(candidate.id()))
                .sorted(Comparator.comparing(
                                (AssetSummary candidate) -> !Objects.equals(candidate.labRoomId(), asset.labRoomId()))
                        .thenComparing(AssetSummary::id))
                .map(AssetSummary::id)
                .toList();
    }

    /**
     * Validates reservation slot rules (delegating to {@link ReservationSlotValidator}).
     */
    public void validateSlotRules(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        ReservationSlotValidator.validateSlotRules(start, end, now);
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
        if (hasReservationOverlap(assetId, start, end, ignoreReservationId)) {
            return false;
        }
        // Reassignment strategies pass ignoreReservationId so we exclude the booking owner's own hold
        // (just like moveToAsset). Unscoped checks (e.g. Project "Reserve all") exclude the caller's own hold.
        Long holdOwnerId = ignoreReservationId != null
                ? reservationRepository
                        .findById(ignoreReservationId)
                        .map(Reservation::getUserId)
                        .orElse(null)
                : ReservationServiceSupport.currentUserIdOrNull(currentUser);
        return !hasActiveHoldOverlap(assetId, start, end, holdOwnerId);
    }

    // Active holds count as busy, so the engine doesn't move a booking into a slot another student holds.
    // moveToAsset already holds the Reservation entity, so it passes r.getUserId() directly without a redundant query.
    private boolean isAssetFreeExceptHoldsOf(
            Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId, Long holdOwnerId) {
        return !hasReservationOverlap(assetId, start, end, ignoreReservationId)
                && !hasActiveHoldOverlap(assetId, start, end, holdOwnerId);
    }

    private boolean hasReservationOverlap(
            Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId) {
        long ignore = ignoreReservationId == null ? NO_RESERVATION_ID : ignoreReservationId;
        return reservationRepository.existsOverlap(assetId, start, end, ignore);
    }

    private boolean hasActiveHoldOverlap(Long assetId, LocalDateTime start, LocalDateTime end, Long holdOwnerId) {
        return reservationHoldRepository.existsActiveOverlap(assetId, start, end, now(), null, holdOwnerId);
    }

    public List<Long> findBusyAssetIds(LocalDateTime start, LocalDateTime end) {
        Set<Long> busy = new LinkedHashSet<>(reservationRepository.findBusyAssetIds(start, end));
        busy.addAll(reservationHoldRepository.findHeldAssetIds(
                start, end, now(), ReservationServiceSupport.currentUserIdOrNull(currentUser)));
        return new ArrayList<>(busy);
    }

    // noRollbackFor keeps the caller's REQUIRES_NEW transaction alive when ReassignmentService catches
    // SubstituteNoLongerFreeException and moves on to the next substitute.
    @Override
    @Transactional(noRollbackFor = SubstituteNoLongerFreeException.class)
    public Optional<Long> moveToAsset(Long reservationId, Long expectedSourceAssetId, Long newAssetId, String reason) {
        // Lock the reservation row before the substitute asset (Reservation -> Asset -> Transfer, #270, #362)
        // so concurrent incident/retire handlers serialize and return the current locked owner userId (#362 §2.1).
        Reservation r = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (r == null
                || r.getStatus() != ReservationStatus.CONFIRMED
                || (expectedSourceAssetId != null && !expectedSourceAssetId.equals(r.getAssetId()))) {
            return Optional.empty();
        }

        catalogApi.lockForBooking(newAssetId);

        if (!catalogApi.isBookable(newAssetId)
                || !isAssetFreeExceptHoldsOf(newAssetId, r.getStartTime(), r.getEndTime(), r.getId(), r.getUserId())) {
            throw new SubstituteNoLongerFreeException("Substitute asset " + newAssetId + " is not free");
        }

        r.moveTo(newAssetId);
        transferService.cancelPendingTransferIfExists(
                reservationId,
                now(),
                null,
                "Auto-cancelled due to equipment reassignment to asset #" + newAssetId,
                "The transfer offer for reservation #"
                        + reservationId
                        + " was cancelled due to equipment reassignment.");
        auditService.record(null, "REASSIGN_RESERVATION", "Reservation", r.getId(), reason);
        return Optional.of(r.getUserId());
    }

    @Override
    @Transactional
    public Optional<Long> forceCancel(Long reservationId, Long expectedSourceAssetId, String reason) {
        // Lock the reservation row so a stale concurrent handler cannot force-cancel a booking that
        // another handler just moved to a healthy substitute (#319), and return the locked owner userId (#362 §2.1).
        Reservation r = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (r == null
                || r.getStatus() != ReservationStatus.CONFIRMED
                || (expectedSourceAssetId != null && !expectedSourceAssetId.equals(r.getAssetId()))) {
            return Optional.empty();
        }

        LocalDateTime now = now();
        r.forceCancel(now, reason);
        transferService.cancelPendingTransferIfExists(
                reservationId,
                now,
                null,
                "Auto-cancelled due to equipment force cancellation: " + reason,
                "The transfer offer for reservation #"
                        + reservationId
                        + " was cancelled due to equipment force cancellation.");
        auditService.record(null, "FORCE_CANCEL_RESERVATION", "Reservation", r.getId(), reason);
        return Optional.of(r.getUserId());
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
     * Cancels all upcoming CONFIRMED reservations for the specified user (e.g. when an admin disables their account).
     *
     * <p>Auto-cancels any pending outgoing transfer offers for those reservations so they do not remain orphaned.
     * Active 1-minute slot holds expire on their own, and incoming transfer offers where the disabled user is the
     * recipient remain {@code PENDING} (accepting is blocked by the disabled account).
     * Equipment already checked out (CHECKED_OUT or OVERDUE) is not modified as physical return is required at the desk.
     *
     * @param userId the ID of the user whose upcoming bookings should be cancelled
     * @param reason the cancellation explanation (max 255 characters)
     * @return the number of upcoming reservations cancelled
     */
    @Override
    @Transactional
    public int cancelUpcomingReservationsForUser(Long userId, String reason) {
        if (reason != null && reason.length() > 255) {
            throw new InvalidParameterException("Cancellation reason must not exceed 255 characters");
        }
        if (userId == null) {
            return 0;
        }
        if (!userApi.exists(userId)) {
            throw new NotFoundException("USER_NOT_FOUND", "User not found: " + userId);
        }

        // Serialize against concurrent bookings, holds, or transfers for the same student.
        reservationRepository.lockUserQuota(userId);

        LocalDateTime now = now();
        String effectiveReason = (reason != null && !reason.isBlank()) ? reason : "Account disabled by administrator";

        // Lock upcoming rows FOR UPDATE so a concurrent extendReservation waits instead of tripping
        // @Version and rolling back the entire disable-cancel batch (#229, #322).
        List<Reservation> upcoming = reservationRepository.findUpcomingConfirmedByUserForUpdate(userId, now);
        if (upcoming.isEmpty()) {
            return 0;
        }

        Long actorId = ReservationServiceSupport.currentUserIdOrNull(currentUser);
        Set<UUID> notifiedBundles = new HashSet<>();

        for (Reservation r : upcoming) {
            r.cancel(now, effectiveReason);
            transferService.cancelPendingTransferIfExists(
                    r.getId(),
                    now,
                    actorId,
                    "Auto-cancelled due to account status: " + effectiveReason,
                    "The transfer offer for reservation #" + r.getId() + " was cancelled: " + effectiveReason);
            auditService.record(actorId, "CANCEL_RESERVATION", "Reservation", r.getId(), effectiveReason);
            // Send one summary notification per bundle instead of N emails for an N-asset kit.
            if (r.getBundleId() == null) {
                ReservationServiceSupport.notifyUserAfterCommit(
                        notificationService,
                        r.getUserId(),
                        "Reservation Cancelled",
                        "Your reservation #" + r.getId() + " has been cancelled. Reason: " + effectiveReason);
            } else if (notifiedBundles.add(r.getBundleId())) {
                ReservationServiceSupport.notifyUserAfterCommit(
                        notificationService,
                        r.getUserId(),
                        "Reservation Bundle Cancelled",
                        "Your reservation bundle #" + r.getBundleId() + " was cancelled. Reason: " + effectiveReason);
            }
        }

        reservationRepository.saveAll(upcoming);
        log.info(
                "Cancelled {} upcoming reservation(s) for user {} (actorId: {}): {}",
                upcoming.size(),
                userId,
                actorId,
                effectiveReason);
        return upcoming.size();
    }

    /**
     * Computes real equipment usage minutes per asset within {@code [from, to]} for Stefania's
     * Equipment Reliability report (#289).
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> usedMinutesByAsset(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            throw new InvalidParameterException("'from' and 'to' must not be null");
        }
        if (!to.isAfter(from)) {
            return Map.of();
        }

        List<Reservation> checkedOut = reservationRepository.findCheckedOutOverlapping(from, to);
        // Read the shared Clock once so every active (unreturned) checkout is capped at the same instant.
        LocalDateTime now = now();
        Map<Long, Long> totals = new HashMap<>();

        for (Reservation r : checkedOut) {
            LocalDateTime rawEnd = r.getReturnedAt() != null ? r.getReturnedAt() : now;
            LocalDateTime effectiveStart = r.getCheckedOutAt().isAfter(from) ? r.getCheckedOutAt() : from;
            LocalDateTime effectiveEnd = rawEnd.isBefore(to) ? rawEnd : to;
            if (effectiveEnd.isAfter(effectiveStart)) {
                long minutes = Duration.between(effectiveStart, effectiveEnd).toMinutes();
                if (minutes > 0) {
                    totals.merge(r.getAssetId(), minutes, Long::sum);
                }
            }
        }
        return totals;
    }

    /**
     * Admin action to check out equipment for a confirmed reservation at the lab desk.
     *
     * <p>Check-out window: only permitted between 15 minutes before slot start and 30 minutes after slot start.
     * Outside this window, throws {@code CHECKOUT_WINDOW_CLOSED}.
     */
    @Transactional
    public ReservationResponse checkOutReservation(Long id) {
        // Lock order: reservation row, then asset row (same as return, markOverdue, extend).
        Reservation reservation = reservationRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id));

        // Check status before the check-out window so NO_SHOW/CANCELLED bookings get the transition error.
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Cannot check out reservation in status " + reservation.getStatus() + "; expected CONFIRMED");
        }

        LocalDateTime now = now();
        LocalDateTime windowStart = reservation.getStartTime().minusMinutes(15);
        LocalDateTime windowEnd = reservation.getStartTime().plusMinutes(30);

        // Bug fixed (#362 §3): for a 30-minute slot (e.g. 10:00-10:30), startTime + 30m equals endTime;
        // checking out at exact endTime must be rejected so we never create a CHECKED_OUT row that already ended.
        if (now.isBefore(windowStart) || now.isAfter(windowEnd) || !now.isBefore(reservation.getEndTime())) {
            throw new BusinessRuleException(
                    "CHECKOUT_WINDOW_CLOSED",
                    "Check-out is only allowed between 15 minutes before slot start and 30 minutes after slot start (and before slot end)");
        }

        // Lock the asset row SECOND to serialize simultaneous desk check-outs on the same physical equipment
        catalogApi.lockForBooking(reservation.getAssetId());

        // Re-verify status under both locks in case of racing transitions
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Cannot check out reservation in status " + reservation.getStatus() + "; expected CONFIRMED");
        }

        // Run before reservation.checkOut(now) so Hibernate auto-flush doesn't persist CHECKED_OUT first
        // and make findCurrentCheckout return this reservation instead of an earlier OVERDUE holder.

        // 1. Check that asset is bookable (operational). Throws 409 ASSET_NOT_BOOKABLE if marked
        // under maintenance or retired between booking and check-out.
        ReservationSlotValidator.ensureBookable(
                catalogApi, reservation.getAssetId(), "Asset is not available for check-out");

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
        transferService.cancelPendingTransferIfExists(
                reservation.getId(),
                now,
                currentUser.id(),
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
        // Pessimistic lock serializes desk returns against the overdue sweep so neither hits a stale-version error.
        Reservation reservation = reservationRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: " + id));

        catalogApi.lockForBooking(reservation.getAssetId());

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
    public PageResponse<ReservationResponse> getAdminReservations(
            ReservationStatus status, Long userId, Long assetId, LocalDate date, int page, int size, String sortDir) {
        ReservationServiceSupport.validatePagination(page, size);
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
     *
     * @return the owner {@code userId} read under the row lock if transitioned, or empty if skipped
     */
    @Transactional
    public Optional<Long> markNoShow(Long reservationId) {
        // Lock the reservation row FOR UPDATE so a concurrent checkOutReservation or cancelUpcomingReservationsForUser
        // waits instead of causing a STALE_VERSION error (#336 follow-up).
        Reservation r = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.CONFIRMED) {
            return Optional.empty();
        }
        r.markNoShow();
        transferService.cancelPendingTransferIfExists(
                reservationId,
                now(),
                null,
                "Auto-cancelled due to no-show",
                "The transfer offer for reservation #" + reservationId + " was cancelled due to no-show.");
        auditService.record(null, "AUTO_NO_SHOW", "Reservation", r.getId(), "Unclaimed 30m after startTime");
        return Optional.of(r.getUserId());
    }

    /**
     * Marks a single checked-out reservation as {@code OVERDUE} in its own transaction.
     * Called through the {@link ReservationService} proxy by {@link ReservationLifecycleService}
     * so the overdue email is only sent after this transaction commits.
     */
    @Transactional
    public boolean markOverdue(Long reservationId) {
        // Same row lock as returnReservation, so a sweep after a return sees RETURNED and skips it.
        Reservation r = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.CHECKED_OUT) {
            return false;
        }
        catalogApi.lockForBooking(r.getAssetId());
        r.markOverdue();
        auditService.record(null, "MARK_OVERDUE", "Reservation", r.getId(), "Equipment not returned by endTime");
        return true;
    }

    private LocalDateTime now() {
        return ReservationServiceSupport.truncatedNow(clock);
    }
}

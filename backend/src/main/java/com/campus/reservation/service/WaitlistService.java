package com.campus.reservation.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.JoinWaitlistRequest;
import com.campus.reservation.web.WaitlistEntryResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manages the student FIFO waitlist for occupied equipment slots and promotes eligible waiters
 * when slots become free (#59).
 */
@Slf4j
@Service
public class WaitlistService {

    static final int MAX_WAITING_ENTRIES = 5;

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHoldRepository reservationHoldRepository;
    private final CatalogApi catalogApi;
    private final UserApi userApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    public WaitlistService(
            WaitlistEntryRepository waitlistEntryRepository,
            ReservationRepository reservationRepository,
            ReservationHoldRepository reservationHoldRepository,
            CatalogApi catalogApi,
            UserApi userApi,
            CurrentUser currentUser,
            AuditService auditService,
            NotificationService notificationService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.reservationRepository = reservationRepository;
        this.reservationHoldRepository = reservationHoldRepository;
        this.catalogApi = catalogApi;
        this.userApi = userApi;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.clock = clock;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    /**
     * Adds the current user to the FIFO waitlist for an occupied slot.
     */
    @Transactional
    public WaitlistEntryResponse joinWaitlist(JoinWaitlistRequest req) {
        Long userId = currentUser.id();
        Long assetId = req.assetId();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        ReservationSlotValidator.validateSlotRules(req.startTime(), req.endTime(), now);

        // Lock asset row FIRST (asset -> userQuota, matching current main's createReservation/createHold)
        // so a concurrent cancellation + promotion cannot race with SLOT_ALREADY_FREE (#349).
        catalogApi.lockForBooking(assetId);
        AssetSummary asset = catalogApi.getAsset(assetId);
        if (asset.status() == AssetStatus.RETIRED && !currentUser.isAdmin()) {
            // Hide retired assets from students with 404, matching GET /api/v1/assets/{id} (#278, #362).
            throw new NotFoundException("ASSET_NOT_FOUND", "Asset not found: " + assetId);
        }
        if (asset.status() != AssetStatus.AVAILABLE) {
            throw new ConflictException("ASSET_NOT_BOOKABLE", "Cannot join waitlist for a non-bookable asset");
        }

        if (reservationRepository.existsUserOverlapOnAsset(assetId, userId, req.startTime(), req.endTime())) {
            throw new ConflictException(
                    "OWN_RESERVATION_CONFLICT", "You already have an active reservation covering this slot");
        }

        // Acquire per-user advisory lock before duplicate/cap checks so parallel requests from the
        // same student serialize cleanly instead of racing into duplicate WAITING rows (#349).
        reservationRepository.lockUserQuota(userId);
        Role activeRole = ReservationServiceSupport.requireActiveUserAfterLock(userApi, userId);

        if (waitlistEntryRepository.existsByAssetIdAndUserIdAndStartTimeAndEndTimeAndStatus(
                assetId, userId, req.startTime(), req.endTime(), WaitlistStatus.WAITING)) {
            throw new ConflictException(
                    "WAITLIST_ALREADY_JOINED", "You are already on the waitlist for this asset and time slot");
        }

        if (activeRole != Role.ADMIN
                && waitlistEntryRepository.countByUserIdAndStatus(userId, WaitlistStatus.WAITING)
                        >= MAX_WAITING_ENTRIES) {
            throw new ConflictException(
                    "WAITLIST_LIMIT_REACHED",
                    "Student cannot have more than " + MAX_WAITING_ENTRIES + " active waitlist entries");
        }

        boolean hasReservationConflict = reservationRepository.existsOverlap(
                assetId, req.startTime(), req.endTime(), ReservationService.NO_RESERVATION_ID);
        boolean hasHoldConflict = reservationHoldRepository.existsActiveOverlap(
                assetId, req.startTime(), req.endTime(), now, null, userId);
        if (!hasReservationConflict && !hasHoldConflict) {
            throw new ConflictException(
                    "SLOT_ALREADY_FREE", "Slot is currently available; book it directly instead of joining waitlist");
        }

        WaitlistEntry entry = new WaitlistEntry(assetId, userId, req.startTime(), req.endTime(), now);
        WaitlistEntry saved = waitlistEntryRepository.save(entry);
        auditService.record(
                userId,
                "JOIN_WAITLIST",
                "WaitlistEntry",
                saved.getId(),
                "Asset " + assetId + " [" + req.startTime() + ", " + req.endTime() + "]");
        return WaitlistEntryResponse.from(saved);
    }

    /**
     * Lists the current user's waitlist entries, newest first.
     */
    @Transactional(readOnly = true)
    public PageResponse<WaitlistEntryResponse> getMyWaitlist(int page, int size) {
        ReservationServiceSupport.validatePagination(page, size);
        Page<WaitlistEntry> result = waitlistEntryRepository.findByUserIdOrderByCreatedAtDescIdDesc(
                currentUser.id(), PageRequest.of(page, size));
        return PageResponse.from(result, WaitlistEntryResponse::from);
    }

    /**
     * Cancels a waiting entry owned by the caller (or any entry for an admin).
     */
    @Transactional
    public void cancelWaitlistEntry(Long id) {
        Long userId = currentUser.id();
        WaitlistEntry entry = waitlistEntryRepository
                .findByIdForUpdate(id)
                .orElseThrow(
                        () -> new NotFoundException("WAITLIST_ENTRY_NOT_FOUND", "Waitlist entry not found: " + id));
        if (!currentUser.isAdmin() && !entry.getUserId().equals(userId)) {
            throw new NotFoundException("WAITLIST_ENTRY_NOT_FOUND", "Waitlist entry not found: " + id);
        }
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        entry.cancel(now);
        auditService.record(userId, "CANCEL_WAITLIST", "WaitlistEntry", entry.getId(), "Cancelled waitlist entry");
    }

    /**
     * Cancels all active WAITING entries belonging to a disabled user (#349).
     *
     * <p>Deliberately does NOT acquire {@code lockUserQuota}: promotion locks
     * {@code asset -> waitlist_entries -> userQuota}, so holding only {@code waitlist_entries}
     * here avoids a lock-order cycle. Caller {@link UserDisabledReservationListener} runs
     * {@code cancelUpcomingReservationsForUser} first (whose {@code lockUserQuota} drains any
     * in-flight {@code joinWaitlist}), and we also bail out early if the account was already
     * re-enabled.
     */
    @Transactional
    public int cancelWaitingEntriesForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        if (userApi.findActiveRole(userId).isPresent()) {
            // Account was re-enabled before this cleanup ran; leave its entries intact (#349).
            return 0;
        }
        List<WaitlistEntry> waiting =
                waitlistEntryRepository.findByUserIdAndStatusForUpdate(userId, WaitlistStatus.WAITING);
        if (waiting.isEmpty()) {
            return 0;
        }
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        Long actorId = ReservationServiceSupport.currentUserIdOrNull(currentUser);
        for (WaitlistEntry entry : waiting) {
            entry.cancel(now);
            auditService.record(
                    actorId, "CANCEL_WAITLIST", "WaitlistEntry", entry.getId(), "Cancelled due to account disable");
        }
        waitlistEntryRepository.saveAll(waiting);
        return waiting.size();
    }

    /**
     * Scans WAITING entries for {@code assetId} in strict FIFO order, executing at most one
     * user-quota-locked promotion, skip, or disable-expiry step per {@code REQUIRES_NEW} transaction
     * so concurrent promotions across different assets never hold multiple user locks and deadlock
     * (#349, #350).
     */
    public int processPromotionsForAsset(Long assetId) {
        int promotedCount = 0;
        Set<Long> visitedEntryIds = new HashSet<>();
        while (true) {
            PromotionStepOutcome outcome =
                    requiresNewTx.execute(status -> processNextPromotionStep(assetId, visitedEntryIds));
            if (outcome == null || outcome == PromotionStepOutcome.DONE) {
                return promotedCount;
            }
            if (outcome == PromotionStepOutcome.PROMOTED) {
                promotedCount++;
            }
        }
    }

    private enum PromotionStepOutcome {
        PROMOTED,
        CONTINUE,
        DONE
    }

    private PromotionStepOutcome processNextPromotionStep(Long assetId, Set<Long> visitedEntryIds) {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        catalogApi.lockForBooking(assetId);
        if (!catalogApi.isBookable(assetId)) {
            return PromotionStepOutcome.DONE;
        }

        List<WaitlistEntry> waiting =
                waitlistEntryRepository.findByAssetIdAndStatusForUpdate(assetId, WaitlistStatus.WAITING);

        for (WaitlistEntry entry : waiting) {
            if (entry.getId() != null && !visitedEntryIds.add(entry.getId())) {
                continue;
            }
            if (!entry.getStartTime().isAfter(now)) {
                entry.markExpired(now);
                continue;
            }
            if (reservationRepository.existsOverlap(
                            assetId, entry.getStartTime(), entry.getEndTime(), ReservationService.NO_RESERVATION_ID)
                    || reservationHoldRepository.existsActiveOverlap(
                            assetId, entry.getStartTime(), entry.getEndTime(), now, null, entry.getUserId())) {
                continue;
            }

            // Lock only ONE user quota per REQUIRES_NEW transaction before reading role/active count (#310, #349).
            // Committing immediately after handling this waiter releases the lock before moving to the next.
            reservationRepository.lockUserQuota(entry.getUserId());
            Optional<Role> activeRole = userApi.findActiveRole(entry.getUserId());
            if (activeRole.isEmpty()) {
                entry.markExpired(now);
                return PromotionStepOutcome.CONTINUE;
            }
            if (activeRole.get() == Role.STUDENT
                    && reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                                    entry.getUserId(), ReservationStatus.ACTIVE_STATUSES)
                            >= ReservationServiceSupport.MAX_ACTIVE_RESERVATIONS) {
                entry.markSkippedQuotaFull(now);
                auditService.record(
                        null,
                        "SKIP_WAITLIST_QUOTA_FULL",
                        "WaitlistEntry",
                        entry.getId(),
                        "User " + entry.getUserId() + " reached active booking limit");
                return PromotionStepOutcome.CONTINUE;
            }

            // Confirm any overlapping active hold already owned by the promoted waiter.
            ReservationServiceSupport.confirmOwnedHoldsForSlots(
                    reservationHoldRepository,
                    entry.getUserId(),
                    List.of(assetId),
                    entry.getStartTime(),
                    entry.getEndTime(),
                    now);

            Reservation promoted = reservationRepository.save(
                    new Reservation(entry.getUserId(), assetId, entry.getStartTime(), entry.getEndTime(), now));
            entry.markPromoted(promoted.getId(), now);

            auditService.record(
                    null,
                    "PROMOTE_WAITLIST",
                    "Reservation",
                    promoted.getId(),
                    "Promoted user " + entry.getUserId() + " from waitlist #" + entry.getId() + " for asset "
                            + assetId);
            ReservationServiceSupport.notifyUserAfterCommit(
                    notificationService,
                    entry.getUserId(),
                    "Waitlist Spot Confirmed",
                    "A slot opened up and your waitlist request #"
                            + entry.getId()
                            + " was promoted to confirmed reservation #"
                            + promoted.getId()
                            + ".");
            return PromotionStepOutcome.PROMOTED;
        }
        return PromotionStepOutcome.DONE;
    }

    /**
     * Expires WAITING entries whose start time has already passed.
     */
    @Transactional
    public int expireStaleEntries() {
        return waitlistEntryRepository.expireOldEntries(ReservationServiceSupport.truncatedNow(clock));
    }
}

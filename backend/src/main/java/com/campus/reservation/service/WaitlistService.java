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
import java.util.List;
import java.util.Optional;
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
    private static final int MAX_HEAD_CHANGE_RETRIES = 10;

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
    private final TransactionTemplate requiresNewReadOnlyTx;

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
        TransactionTemplate readOnlyTt = new TransactionTemplate(transactionManager);
        readOnlyTt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnlyTt.setReadOnly(true);
        this.requiresNewReadOnlyTx = readOnlyTt;
    }

    /**
     * Adds the current user to the FIFO waitlist for an occupied slot.
     *
     * <p>Follows the global lock DAG ({@code Quota -> Reservation -> Asset -> Transfer -> Waitlist -> Hold},
     * #349, #370): acquires {@code lockUserQuota(userId)} FIRST, verifies active account/role, then locks
     * the asset row before checking overlaps and inserting the {@code WAITING} entry.
     */
    @Transactional
    public WaitlistEntryResponse joinWaitlist(JoinWaitlistRequest req) {
        Long userId = currentUser.id();
        Long assetId = req.assetId();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        ReservationSlotValidator.validateSlotRules(req.startTime(), req.endTime(), now);

        // 1. Lock caller quota FIRST (Quota -> Asset, matching #370 global lock DAG) so parallel requests
        // from the same student serialize cleanly and never AB-BA deadlock with createReservation/createHold.
        reservationRepository.lockUserQuota(userId);
        Role activeRole = ReservationServiceSupport.requireActiveUserAfterLock(userApi, userId);

        // 2. Lock asset row SECOND so a concurrent cancellation + promotion cannot race with SLOT_ALREADY_FREE.
        catalogApi.lockForBooking(assetId);
        AssetSummary asset = catalogApi.getAsset(assetId);
        if (asset.status() == AssetStatus.RETIRED && activeRole != Role.ADMIN) {
            // Hide retired assets from students with the exact AssetService detail message (#278, #349, #362).
            throw new NotFoundException("ASSET_NOT_FOUND", "Asset " + assetId + " was not found");
        }
        if (asset.status() != AssetStatus.AVAILABLE) {
            throw new ConflictException("ASSET_NOT_BOOKABLE", "Cannot join waitlist for a non-bookable asset");
        }

        // Checked under both Quota and Asset locks to close concurrent acceptTransfer-vs-joinWaitlist races (#349).
        if (reservationRepository.existsUserOverlapOnAsset(assetId, userId, req.startTime(), req.endTime())) {
            throw new ConflictException(
                    "OWN_RESERVATION_CONFLICT", "You already have an active reservation covering this slot");
        }

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
     * Cancels all active WAITING entries belonging to a disabled user (#349, #370).
     *
     * <p>Follows the global lock DAG ({@code Quota -> Waitlist}): locks {@code lockUserQuota(userId)} first
     * to serialize with any in-flight {@code joinWaitlist} or promotion for this user and re-checks
     * {@code userApi.findActiveRole(userId)} so a re-enabled account keeps its entries intact.
     */
    @Transactional
    public int cancelWaitingEntriesForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        reservationRepository.lockUserQuota(userId);
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
     * user-quota-locked promotion, skip, or disable-expiry step per {@code REQUIRES_NEW} transaction.
     *
     * <p>Uses a lock-free read-only peek to identify the next FIFO candidate's {@code userId}, then
     * acquires locks in strict global DAG order ({@code Quota -> Asset -> Waitlist -> Hold}, #349, #370)
     * so concurrent promotions never AB-BA deadlock with {@code createReservation}/{@code createHold}/
     * {@code joinWaitlist} or account disables. Never caches skipped occupied entries across iterations:
     * if an earlier slot frees mid-promotion, it is re-evaluated first in strict FIFO order.
     */
    public int processPromotionsForAsset(Long assetId) {
        int promotedCount = 0;
        int headChangeRetries = 0;
        while (true) {
            PromotionPeek peek = requiresNewReadOnlyTx.execute(status -> peekNextCandidate(assetId));
            if (peek == null || peek.kind() == PeekKind.NONE) {
                return promotedCount;
            }
            if (peek.kind() == PeekKind.EXPIRE_ONLY) {
                requiresNewTx.executeWithoutResult(status -> expirePastEntriesForAsset(assetId));
                return promotedCount;
            }
            if (peek.kind() == PeekKind.EXPIRE_RETIRED) {
                requiresNewTx.executeWithoutResult(status -> expireRetiredAssetEntries(assetId));
                return promotedCount;
            }
            Long candidateUserId = peek.userId();
            PromotionStepOutcome outcome =
                    requiresNewTx.execute(status -> processNextPromotionStep(assetId, candidateUserId));
            if (outcome == null || outcome == PromotionStepOutcome.DONE) {
                return promotedCount;
            }
            if (outcome == PromotionStepOutcome.RETRY_FOR_EARLIER_HEAD) {
                if (++headChangeRetries > MAX_HEAD_CHANGE_RETRIES) {
                    log.warn(
                            "Stopping waitlist promotion for asset #{} after {} concurrent head-change retries",
                            assetId,
                            MAX_HEAD_CHANGE_RETRIES);
                    return promotedCount;
                }
                continue;
            }
            headChangeRetries = 0;
            if (outcome == PromotionStepOutcome.PROMOTED) {
                promotedCount++;
            }
        }
    }

    private enum PeekKind {
        CANDIDATE,
        EXPIRE_ONLY,
        EXPIRE_RETIRED,
        NONE
    }

    private record PromotionPeek(PeekKind kind, Long userId) {
        static PromotionPeek candidate(Long userId) {
            return new PromotionPeek(PeekKind.CANDIDATE, userId);
        }

        static PromotionPeek expireOnly() {
            return new PromotionPeek(PeekKind.EXPIRE_ONLY, null);
        }

        static PromotionPeek expireRetired() {
            return new PromotionPeek(PeekKind.EXPIRE_RETIRED, null);
        }

        static PromotionPeek none() {
            return new PromotionPeek(PeekKind.NONE, null);
        }
    }

    private enum PromotionStepOutcome {
        PROMOTED,
        CONTINUE,
        RETRY_FOR_EARLIER_HEAD,
        DONE
    }

    private PromotionPeek peekNextCandidate(Long assetId) {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        AssetSummary asset = catalogApi.getAsset(assetId);
        if (asset.status() == AssetStatus.RETIRED) {
            return PromotionPeek.expireRetired();
        }
        if (asset.status() != AssetStatus.AVAILABLE) {
            return PromotionPeek.none();
        }
        List<WaitlistEntry> waiting =
                waitlistEntryRepository.findByAssetIdAndStatusOrderByCreatedAtAscIdAsc(assetId, WaitlistStatus.WAITING);
        boolean foundExpired = false;
        for (WaitlistEntry entry : waiting) {
            if (entry.getStatus() != WaitlistStatus.WAITING) {
                continue;
            }
            if (!entry.getStartTime().isAfter(now)) {
                foundExpired = true;
                continue;
            }
            if (reservationRepository.existsOverlap(
                            assetId, entry.getStartTime(), entry.getEndTime(), ReservationService.NO_RESERVATION_ID)
                    || reservationHoldRepository.existsActiveOverlap(
                            assetId, entry.getStartTime(), entry.getEndTime(), now, null, entry.getUserId())) {
                continue;
            }
            return PromotionPeek.candidate(entry.getUserId());
        }
        return foundExpired ? PromotionPeek.expireOnly() : PromotionPeek.none();
    }

    private void expireRetiredAssetEntries(Long assetId) {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        catalogApi.lockForBooking(assetId);
        if (catalogApi.getAsset(assetId).status() != AssetStatus.RETIRED) {
            return;
        }
        List<WaitlistEntry> waiting =
                waitlistEntryRepository.findByAssetIdAndStatusForUpdate(assetId, WaitlistStatus.WAITING);
        for (WaitlistEntry entry : waiting) {
            if (entry.getStatus() == WaitlistStatus.WAITING) {
                entry.markExpired(now);
            }
        }
    }

    private void expirePastEntriesForAsset(Long assetId) {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        catalogApi.lockForBooking(assetId);
        List<WaitlistEntry> waiting =
                waitlistEntryRepository.findByAssetIdAndStatusForUpdate(assetId, WaitlistStatus.WAITING);
        for (WaitlistEntry entry : waiting) {
            if (entry.getStatus() == WaitlistStatus.WAITING
                    && !entry.getStartTime().isAfter(now)) {
                entry.markExpired(now);
            }
        }
    }

    private PromotionStepOutcome processNextPromotionStep(Long assetId, Long expectedUserId) {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

        // Global lock DAG (#349, #370): Quota FIRST, Asset SECOND, Waitlist THIRD, Hold LAST.
        reservationRepository.lockUserQuota(expectedUserId);
        catalogApi.lockForBooking(assetId);
        AssetSummary asset = catalogApi.getAsset(assetId);
        if (asset.status() == AssetStatus.RETIRED) {
            // Retirement is permanent: expire any remaining WAITING rows once so future sweeps never
            // re-lock this retired asset (#350).
            List<WaitlistEntry> waitingOnRetired =
                    waitlistEntryRepository.findByAssetIdAndStatusForUpdate(assetId, WaitlistStatus.WAITING);
            for (WaitlistEntry entry : waitingOnRetired) {
                entry.markExpired(now);
            }
            return PromotionStepOutcome.DONE;
        }
        if (asset.status() != AssetStatus.AVAILABLE) {
            return PromotionStepOutcome.DONE;
        }

        List<WaitlistEntry> waiting =
                waitlistEntryRepository.findByAssetIdAndStatusForUpdate(assetId, WaitlistStatus.WAITING);

        for (WaitlistEntry entry : waiting) {
            // Bug fixed (#349): previously tracked visitedEntryIds across iterations before checking slot overlap,
            // which caused an earlier entry whose slot freed mid-promotion to be skipped out of FIFO order.
            // Fix: check live status == WAITING instead of caching skipped IDs across transactions.
            if (entry.getStatus() != WaitlistStatus.WAITING) {
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

            // If an earlier FIFO waiter for a different user became promotable between peek and locking,
            // release our locks and re-peek so we lock that waiter's quota first in strict FIFO order.
            if (!entry.getUserId().equals(expectedUserId)) {
                return PromotionStepOutcome.RETRY_FOR_EARLIER_HEAD;
            }

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
     * Returns distinct asset IDs that currently have at least one WAITING entry (#350).
     */
    @Transactional(readOnly = true)
    public List<Long> findAssetIdsWithWaitingEntries() {
        return waitlistEntryRepository.findDistinctAssetIdsByStatus(WaitlistStatus.WAITING);
    }

    /**
     * Expires WAITING entries whose start time has already passed.
     */
    @Transactional
    public int expireStaleEntries() {
        return waitlistEntryRepository.expireOldEntries(ReservationServiceSupport.truncatedNow(clock));
    }
}

package com.campus.reservation.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the student FIFO waitlist for occupied equipment slots and promotes eligible waiters
 * when slots become free (#59).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
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

    /**
     * Adds the current user to the FIFO waitlist for an occupied slot.
     */
    @Transactional
    public WaitlistEntryResponse joinWaitlist(JoinWaitlistRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        ReservationSlotValidator.validateSlotRules(req.startTime(), req.endTime(), now);

        AssetSummary asset = catalogApi.getAsset(req.assetId());
        ReservationSlotValidator.ensureBookable(
                catalogApi, asset.id(), "Cannot join waitlist for a non-bookable asset");

        if (reservationRepository.existsUserOverlapOnAsset(asset.id(), userId, req.startTime(), req.endTime())) {
            throw new ConflictException(
                    "OWN_RESERVATION_CONFLICT", "You already have an active reservation covering this slot");
        }

        // Acquire per-user advisory lock before duplicate/cap checks so parallel requests from the
        // same student serialize cleanly instead of racing into duplicate WAITING rows (#349).
        reservationRepository.lockUserQuota(userId);
        Role activeRole = ReservationServiceSupport.requireActiveUserAfterLock(userApi, userId);

        if (activeRole != Role.ADMIN
                && waitlistEntryRepository.countByUserIdAndStatus(userId, WaitlistStatus.WAITING)
                        >= MAX_WAITING_ENTRIES) {
            throw new ConflictException(
                    "WAITLIST_LIMIT_REACHED",
                    "Student cannot have more than " + MAX_WAITING_ENTRIES + " active waitlist entries");
        }

        if (waitlistEntryRepository.existsByAssetIdAndUserIdAndStartTimeAndEndTimeAndStatus(
                asset.id(), userId, req.startTime(), req.endTime(), WaitlistStatus.WAITING)) {
            throw new ConflictException(
                    "WAITLIST_ALREADY_JOINED", "You are already on the waitlist for this asset and time slot");
        }

        boolean hasReservationConflict = reservationRepository.existsOverlap(
                asset.id(), req.startTime(), req.endTime(), ReservationService.NO_RESERVATION_ID);
        boolean hasHoldConflict = reservationHoldRepository.existsActiveOverlap(
                asset.id(), req.startTime(), req.endTime(), now, null, userId);
        if (!hasReservationConflict && !hasHoldConflict) {
            throw new ConflictException(
                    "SLOT_ALREADY_FREE", "Slot is currently available; book it directly instead of joining waitlist");
        }

        WaitlistEntry entry = new WaitlistEntry(asset.id(), userId, req.startTime(), req.endTime(), now);
        WaitlistEntry saved = waitlistEntryRepository.save(entry);
        auditService.record(
                userId,
                "JOIN_WAITLIST",
                "WaitlistEntry",
                saved.getId(),
                "Asset " + asset.id() + " [" + req.startTime() + ", " + req.endTime() + "]");
        return WaitlistEntryResponse.from(saved);
    }

    /**
     * Lists the current user's waitlist entries, newest first.
     */
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
     */
    @Transactional
    public int cancelWaitingEntriesForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        reservationRepository.lockUserQuota(userId);
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
     * Scans WAITING entries for {@code assetId} in strict FIFO order under row locks and promotes
     * any whose slot is now free, following {@code asset -> waitlist_entries -> userQuota} lock order.
     */
    @Transactional
    public int processPromotionsForAsset(Long assetId) {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        catalogApi.lockForBooking(assetId);
        if (!catalogApi.isBookable(assetId)) {
            return 0;
        }

        List<WaitlistEntry> waiting =
                waitlistEntryRepository.findByAssetIdAndStatusForUpdate(assetId, WaitlistStatus.WAITING);
        int promotedCount = 0;

        for (WaitlistEntry entry : waiting) {
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

            // Lock user quota before reading role/active count, matching createReservation (#310).
            reservationRepository.lockUserQuota(entry.getUserId());
            Optional<Role> activeRole = userApi.findActiveRole(entry.getUserId());
            if (activeRole.isEmpty()) {
                entry.markExpired(now);
                continue;
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
                continue;
            }

            // Confirm any matching active hold already owned by the promoted waiter.
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
            promotedCount++;

            auditService.record(
                    entry.getUserId(),
                    "PROMOTE_WAITLIST",
                    "Reservation",
                    promoted.getId(),
                    "Promoted from waitlist #" + entry.getId() + " for asset " + assetId);
            ReservationServiceSupport.notifyUserAfterCommit(
                    notificationService,
                    entry.getUserId(),
                    "Waitlist Spot Confirmed",
                    "A slot opened up and your waitlist request #"
                            + entry.getId()
                            + " was promoted to confirmed reservation #"
                            + promoted.getId()
                            + ".");
        }
        return promotedCount;
    }

    /**
     * Expires WAITING entries whose start time has already passed.
     */
    @Transactional
    public int expireStaleEntries() {
        return waitlistEntryRepository.expireOldEntries(ReservationServiceSupport.truncatedNow(clock));
    }
}

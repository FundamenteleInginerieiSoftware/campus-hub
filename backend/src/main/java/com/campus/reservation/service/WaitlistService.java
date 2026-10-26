package com.campus.reservation.service;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.WaitlistEntryResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the FIFO waitlist for occupied time slots and automatically promotes the first eligible
 * waiting student into a confirmed reservation when a slot opens up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WaitlistService {

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final CatalogApi catalogApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    /**
     * Enqueues the current student onto the waitlist for a slot that currently has an overlapping booking.
     * Reuses {@link ReservationService#validateSlotRules} so promoted waitlist entries obey the exact
     * same booking constraints (:00/:30 alignment, 30m-4h duration, 14-day horizon, Mon-Fri 08:00-20:00).
     */
    @Transactional
    public WaitlistEntryResponse joinWaitlist(CreateReservationRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start = req.startTime();
        LocalDateTime end = req.endTime();

        reservationService.validateSlotRules(start, end, now);

        if (!catalogApi.isBookable(req.assetId())) {
            throw new ConflictException("ASSET_NOT_BOOKABLE", "Asset is not available for booking");
        }

        // Waitlist only makes sense when the slot is currently occupied by an active reservation
        boolean currentlyOccupied =
                reservationRepository.existsOverlap(req.assetId(), start, end, ReservationStatus.ACTIVE_STATUSES, -1L);
        if (!currentlyOccupied) {
            throw new ConflictException(
                    "SLOT_ALREADY_FREE", "The requested time slot is already free; book it directly instead");
        }

        if (waitlistEntryRepository.existsOverlappingWaitlistForUser(userId, req.assetId(), start, end)) {
            throw new ConflictException(
                    "ALREADY_ON_WAITLIST", "You already have a waiting entry for this asset and time window");
        }

        WaitlistEntry entry = new WaitlistEntry(userId, req.assetId(), start, end, now);
        WaitlistEntry saved = waitlistEntryRepository.save(entry);
        auditService.record(userId, "JOIN_WAITLIST", "WaitlistEntry", saved.getId(), "Asset " + req.assetId());

        return WaitlistEntryResponse.from(saved);
    }

    /**
     * Returns all waitlist entries for the authenticated student.
     */
    public List<WaitlistEntryResponse> getMyWaitlist() {
        Long userId = currentUser.id();
        return waitlistEntryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(WaitlistEntryResponse::from)
                .toList();
    }

    /**
     * Cancels a waiting entry owned by the current student (or any entry if admin).
     * Returns 404 (not 403) if the entry belongs to another student, per IDOR protection rules.
     */
    @Transactional
    public WaitlistEntryResponse cancelWaitlistEntry(Long waitlistId) {
        Long userId = currentUser.id();
        WaitlistEntry entry = waitlistEntryRepository
                .findById(waitlistId)
                .orElseThrow(
                        () -> new NotFoundException("WAITLIST_NOT_FOUND", "Waitlist entry not found: " + waitlistId));

        if (!currentUser.isAdmin() && !entry.getUserId().equals(userId)) {
            throw new NotFoundException("WAITLIST_NOT_FOUND", "Waitlist entry not found: " + waitlistId);
        }

        entry.cancel(LocalDateTime.now(clock));
        auditService.record(userId, "CANCEL_WAITLIST", "WaitlistEntry", entry.getId(), "Asset " + entry.getAssetId());
        return WaitlistEntryResponse.from(entry);
    }

    /**
     * Evaluates the FIFO waitlist queue for {@code assetId} under a pessimistic asset row lock and
     * promotes every waiting entry whose requested interval is now conflict-free and whose owner is
     * within the 2-active-bookings quota.
     */
    @Transactional
    public List<WaitlistEntryResponse> processPromotionsForAsset(Long assetId) {
        LocalDateTime now = LocalDateTime.now(clock);
        catalogApi.lockForBooking(assetId);

        if (!catalogApi.isBookable(assetId)) {
            return List.of();
        }

        List<WaitlistEntry> queue = waitlistEntryRepository.findWaitingByAssetOrderByFifo(assetId, now);
        List<WaitlistEntryResponse> promoted = new ArrayList<>();

        for (WaitlistEntry candidate : queue) {
            boolean hasConflict = reservationRepository.existsOverlap(
                    assetId, candidate.getStartTime(), candidate.getEndTime(), ReservationStatus.ACTIVE_STATUSES, -1L);
            if (hasConflict) {
                continue;
            }

            long activeCount = reservationRepository.countByUserIdAndStatusIn(
                    candidate.getUserId(), ReservationStatus.ACTIVE_STATUSES);
            if (activeCount >= 2) {
                candidate.markSkippedQuotaFull(now);
                continue;
            }

            Reservation reservation = new Reservation(
                    candidate.getUserId(), assetId, candidate.getStartTime(), candidate.getEndTime(), now);
            Reservation savedReservation = reservationRepository.save(reservation);
            candidate.markPromoted(savedReservation.getId(), now);

            auditService.record(
                    candidate.getUserId(),
                    "WAITLIST_PROMOTED",
                    "Reservation",
                    savedReservation.getId(),
                    "Auto-promoted from waitlist #" + candidate.getId());

            notifySafely(
                    candidate.getUserId(),
                    "Waitlist Slot Promoted to Confirmed Reservation",
                    "Good news! Your waitlisted slot on asset #" + assetId + " ("
                            + candidate.getStartTime() + " - " + candidate.getEndTime()
                            + ") became free and has been automatically booked as reservation #"
                            + savedReservation.getId() + ".");

            promoted.add(WaitlistEntryResponse.from(candidate));
        }

        return promoted;
    }

    /**
     * Periodic background sweep that expires past waitlist entries and promotes waiting entries
     * whenever cancellations or no-shows free up a slot.
     */
    @Scheduled(fixedDelayString = "${app.reservation.waitlist-sweep-ms:60000}")
    @Transactional
    public void scheduledWaitlistSweep() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<WaitlistEntry> waiting = waitlistEntryRepository.findByStatusOrderByCreatedAtAsc(WaitlistStatus.WAITING);

        waiting.stream().filter(w -> !w.getStartTime().isAfter(now)).forEach(w -> w.markExpired(now));

        waiting.stream()
                .filter(w -> w.getStatus() == WaitlistStatus.WAITING)
                .map(WaitlistEntry::getAssetId)
                .distinct()
                .forEach(this::processPromotionsForAsset);
    }

    private void notifySafely(Long userId, String subject, String body) {
        try {
            notificationService.notifyUser(userId, subject, body);
        } catch (Exception e) {
            log.warn("Failed to notify user {} after waitlist promotion: {}", userId, e.getMessage());
        }
    }
}

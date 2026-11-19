package com.campus.reservation.service;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateHoldRequest;
import com.campus.reservation.web.ReservationHoldResponse;
import com.campus.reservation.web.ReservationResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing temporary 1-minute reservation holds (slot locking, confirmation, early release, and expiry sweep).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldService {

    private final ReservationHoldRepository reservationHoldRepository;
    private final ReservationRepository reservationRepository;
    private final CatalogApi catalogApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final UserApi userApi;
    private final Clock clock;

    /**
     * Temporarily holds a lab asset time slot for 1 minute.
     *
     * <p>Concurrency note: Acquires a pessimistic row lock on the asset in catalog FIRST before
     * checking overlaps, ensuring simultaneous requests cannot double-hold the same asset slot.
     */
    @Transactional
    public ReservationHoldResponse createHold(CreateHoldRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

        ReservationSlotValidator.validateSlotRules(req.startTime(), req.endTime(), now);
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

        if (!currentUser.isAdmin()) {
            // Anti-hoarding hold cooldown guard (#240 point 4):
            // Prevent repeated hold -> release -> hold loops across overlapping windows by requiring a 2-minute
            // cooldown.
            LocalDateTime cooldownThreshold = now.minusMinutes(2);
            if (reservationHoldRepository.existsRecentHoldOverlap(
                    userId, req.assetId(), req.startTime(), req.endTime(), cooldownThreshold)) {
                throw new ConflictException(
                        "HOLD_COOLDOWN_ACTIVE",
                        "You recently held an overlapping slot on this equipment. Please wait before holding it again or book it directly.");
            }

            reservationRepository.lockUserQuota(userId);
            ReservationServiceSupport.requireActiveUserAfterLock(userApi, userId);

            long activeHolds =
                    reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(userId, HoldStatus.ACTIVE, now);
            if (activeHolds >= 1) {
                throw new ConflictException(
                        "ACTIVE_HOLD_LIMIT_REACHED",
                        "You already have an active reservation hold. Please confirm or release it before holding another slot.");
            }

            long activeReservations = reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                    userId, ReservationStatus.ACTIVE_STATUSES);
            if (activeReservations >= 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot exceed active reservation quota (max 2)");
            }
        }

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
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

        ReservationHold hold = reservationHoldRepository
                .findByIdForUpdate(holdId)
                .orElseThrow(() -> new NotFoundException("HOLD_NOT_FOUND", "Reservation hold not found"));

        if (!hold.getUserId().equals(userId) && !currentUser.isAdmin()) {
            throw new NotFoundException("HOLD_NOT_FOUND", "Reservation hold not found");
        }

        hold.confirm(now);

        // Re-validate slot rules at confirmation time to prevent past-start confirmations (#228 point 9)
        ReservationSlotValidator.validateSlotRules(hold.getStartTime(), hold.getEndTime(), now);
        ReservationSlotValidator.lockAndValidateSlotFree(
                catalogApi,
                reservationRepository,
                reservationHoldRepository,
                hold.getAssetId(),
                hold.getStartTime(),
                hold.getEndTime(),
                now,
                hold.getId(),
                hold.getUserId());

        Long studentId = hold.getUserId();
        // Enforce quota whenever the hold owner is a student, including when confirmed by an admin (#228 point 9).
        boolean adminConfirmingOwnHold = studentId.equals(userId) && currentUser.isAdmin();
        boolean holdOwnerIsStudent = !adminConfirmingOwnHold
                && userApi.findActiveRole(studentId).map(r -> r == Role.STUDENT).orElse(true);
        if (holdOwnerIsStudent) {
            reservationRepository.lockUserQuota(studentId);
            ReservationServiceSupport.requireActiveUserAfterLock(userApi, studentId);
            if (reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                            studentId, ReservationStatus.ACTIVE_STATUSES)
                    >= 2) {
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

        ReservationServiceSupport.notifyUserAfterCommit(
                notificationService,
                studentId,
                "Reservation Confirmed",
                "Your reservation #" + savedReservation.getId() + " is confirmed.");

        return ReservationResponse.from(savedReservation);
    }

    /**
     * Releases an active hold early, freeing the slot for other students.
     */
    @Transactional
    public ReservationHoldResponse releaseHold(Long holdId) {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

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
    @Transactional(readOnly = true)
    public List<ReservationHoldResponse> getMyActiveHolds() {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        return reservationHoldRepository.findByUserIdAndStatusAndExpiresAtAfter(userId, HoldStatus.ACTIVE, now).stream()
                .map(h -> ReservationHoldResponse.from(h, now))
                .toList();
    }

    /**
     * Sweeps expired reservation holds in status ACTIVE where expiresAt <= now.
     */
    @Transactional
    public int sweepExpiredHolds(LocalDateTime now) {
        int count = reservationHoldRepository.expireOldHolds(now);
        if (count > 0) {
            log.info("Expired {} abandoned reservation holds", count);
        }
        return count;
    }
}

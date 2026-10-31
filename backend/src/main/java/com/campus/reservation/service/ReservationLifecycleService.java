package com.campus.reservation.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.LifecycleSweepSummary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs autonomous background jobs (`@Scheduled`) and on-demand admin sweeps for:
 * <ul>
 *   <li><b>Auto-No-Show Release:</b> releases unclaimed bookings 30 minutes after start time;</li>
 *   <li><b>Overdue Detection & Preventive Cascade Reassignment:</b> marks unreturned bookings as
 *       {@code OVERDUE} and proactively moves the next student's imminent reservation (starting within
 *       60 minutes) to a free substitute asset before they arrive at the lab.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationLifecycleService {

    private static final int NO_SHOW_GRACE_MINUTES = 30;
    private static final int PREVENTIVE_CASCADE_HORIZON_MINUTES = 60;

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final CatalogApi catalogApi;
    private final NotificationService notificationService;
    private final Clock clock;

    /**
     * Autonomous lifecycle job running every 5 minutes (also callable on demand by admins) to release
     * no-shows and trigger preventive cascade reassignment for overdue equipment.
     *
     * <p>Bug fixed: previously annotated with a single {@code @Transactional} on the whole sweep, which meant an
     * optimistic-lock conflict on one concurrently edited reservation rolled back the entire batch after emails
     * had already been sent. Fix: each reservation state change now runs in its own transaction via
     * {@link ReservationService}, and notification emails are only sent after that reservation's commit succeeds.
     */
    @Scheduled(
            fixedDelayString = "${app.reservation.lifecycle-sweep-ms:300000}",
            initialDelayString = "${app.reservation.lifecycle-initial-delay-ms:60000}")
    public LifecycleSweepSummary runLifecycleSweep() {
        LocalDateTime now = LocalDateTime.now(clock);
        int noShowCount = processNoShows(now);
        List<Long> cascadedIds = new ArrayList<>();
        int overdueCount = processOverdueAndCascade(now, cascadedIds);

        return new LifecycleSweepSummary(now, noShowCount, overdueCount, cascadedIds.size(), cascadedIds);
    }

    private int processNoShows(LocalDateTime now) {
        LocalDateTime cutoff = now.minusMinutes(NO_SHOW_GRACE_MINUTES);
        List<Reservation> unclaimed = reservationRepository.findConfirmedBeforeCutoff(cutoff);
        int releasedCount = 0;

        for (Reservation r : unclaimed) {
            try {
                if (reservationService.markNoShow(r.getId())) {
                    releasedCount++;
                    notifySafely(
                            r.getUserId(),
                            "Reservation Marked as No-Show",
                            "Your reservation #" + r.getId()
                                    + " was automatically released because it was not checked out within 30 minutes of start time.");
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Skipping no-show transition for reservation #{} due to concurrent update: {}",
                        r.getId(),
                        e.getMessage());
            }
        }

        return releasedCount;
    }

    private int processOverdueAndCascade(LocalDateTime now, List<Long> cascadedReservationIds) {
        List<Reservation> expiredCheckouts = reservationRepository.findCheckedOutPastEnd(now);
        int overdueCount = 0;

        for (Reservation overdue : expiredCheckouts) {
            boolean marked;
            try {
                marked = reservationService.markOverdue(overdue.getId());
            } catch (RuntimeException e) {
                log.warn(
                        "Skipping overdue transition for reservation #{} due to concurrent update: {}",
                        overdue.getId(),
                        e.getMessage());
                continue;
            }
            if (!marked) {
                continue;
            }

            overdueCount++;
            notifySafely(
                    overdue.getUserId(),
                    "Equipment Overdue",
                    "Your reservation #" + overdue.getId()
                            + " is past its end time. Please return the equipment to the lab desk immediately.");
        }

        // Bug fixed: previously, the preventive cascade check was only executed once inside the
        // transition block above. If an asset was still missing 1 hour later, upcoming bookings starting
        // then were never moved because findCheckedOutPastEnd only returns CHECKED_OUT rows.
        // Fix: run the preventive check for EVERY asset currently unreturned (status OVERDUE) on every sweep.
        // Since moveToAsset returns false when nothing changes, re-evaluating active OVERDUE assets is safe.
        List<Reservation> currentOverdue = reservationRepository.findByStatus(ReservationStatus.OVERDUE);
        LocalDateTime cascadeHorizon = now.plusMinutes(PREVENTIVE_CASCADE_HORIZON_MINUTES);
        Set<Long> overdueAssetIds = new HashSet<>();
        for (Reservation r : currentOverdue) {
            overdueAssetIds.add(r.getAssetId());
        }

        for (Long overdueAssetId : overdueAssetIds) {
            List<Reservation> upcomingOnSameAsset =
                    reservationRepository.findUpcomingConfirmedByAsset(overdueAssetId, now);

            for (Reservation threatened : upcomingOnSameAsset) {
                if (threatened.getStartTime().isAfter(cascadeHorizon)) {
                    break;
                }
                if (tryPreventiveMove(threatened, overdueAssetId)) {
                    cascadedReservationIds.add(threatened.getId());
                }
            }
        }

        return overdueCount;
    }

    private boolean tryPreventiveMove(Reservation threatened, Long overdueAssetId) {
        AssetSummary currentAsset = catalogApi.getAsset(overdueAssetId);
        List<AssetSummary> substitutes = catalogApi.findAvailableByModel(currentAsset.model(), overdueAssetId);

        for (AssetSummary sub : substitutes) {
            if (!reservationService.isAssetFree(
                    sub.id(), threatened.getStartTime(), threatened.getEndTime(), threatened.getId())) {
                continue;
            }
            try {
                boolean moved = reservationService.moveToAsset(
                        threatened.getId(),
                        sub.id(),
                        "Preventive cascade reassignment: previous holder on asset #" + overdueAssetId + " is OVERDUE");
                if (moved) {
                    notifySafely(
                            threatened.getUserId(),
                            "Proactive Reservation Reassignment",
                            "Because the previous holder has not yet returned asset #" + overdueAssetId
                                    + ", your upcoming reservation #" + threatened.getId()
                                    + " has been proactively moved to substitute asset " + sub.name() + ".");
                }
                return moved;
            } catch (SubstituteNoLongerFreeException e) {
                log.warn(
                        "Substitute {} became busy during preventive cascade for reservation #{}",
                        sub.id(),
                        threatened.getId());
            } catch (RuntimeException e) {
                log.warn(
                        "Skipping preventive move for reservation #{} due to concurrent update: {}",
                        threatened.getId(),
                        e.getMessage());
                return false;
            }
        }
        return false;
    }

    private void notifySafely(Long userId, String subject, String body) {
        try {
            notificationService.notifyUser(userId, subject, body);
        } catch (Exception e) {
            log.warn("Failed to notify user {}: {}", userId, e.getMessage());
        }
    }
}

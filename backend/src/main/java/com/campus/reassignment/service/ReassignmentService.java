package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reassignment engine that reallocates future bookings of a damaged asset to substitute equipment
 * or force-cancels them when no substitute is free.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReassignmentService {

    private final CatalogApi catalogApi;
    private final ReservationApi reservationApi;
    private final NotificationService notificationService;
    private final ReassignmentLogRepository reassignmentLogRepository;
    private final List<SubstituteStrategy> strategies;
    private final Clock clock;

    /**
     * Handles an {@link AssetDamagedEvent} in an isolated transaction so reassignment proceeds
     * independently after incident reporting commits.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(AssetDamagedEvent event) {
        AssetSummary damaged = catalogApi.getAsset(event.assetId());
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationSummary> upcoming = reservationApi.findUpcomingActiveByAsset(event.assetId(), now);

        List<ReservationSummary> sortedBookings = upcoming.stream()
                .sorted(Comparator.comparing(ReservationSummary::start))
                .toList();

        for (ReservationSummary booking : sortedBookings) {
            boolean reassigned = false;

            for (SubstituteStrategy strategy : strategies) {
                Optional<AssetSummary> substituteOpt = strategy.findSubstitute(booking, damaged);
                if (substituteOpt.isPresent()) {
                    AssetSummary candidate = substituteOpt.get();
                    try {
                        reservationApi.moveToAsset(booking.id(), candidate.id(), "Incident #" + event.incidentId());
                        reassigned = true;

                        ReassignmentLog logEntry = new ReassignmentLog(
                                event.incidentId(),
                                booking.id(),
                                event.assetId(),
                                candidate.id(),
                                ReassignmentOutcome.REASSIGNED,
                                strategy.name(),
                                LocalDateTime.now(clock));
                        reassignmentLogRepository.save(logEntry);

                        sendNotificationSafely(
                                booking.userId(),
                                "Reservation Reassigned",
                                "Your reservation #" + booking.id() + " has been reassigned to asset "
                                        + candidate.name() + " due to equipment maintenance.");
                        break;
                    } catch (SubstituteNoLongerFreeException e) {
                        log.warn(
                                "Candidate asset {} was no longer free when moving reservation #{}, trying next option",
                                candidate.id(),
                                booking.id());
                    }
                }
            }

            if (!reassigned) {
                reservationApi.forceCancel(booking.id(), "Equipment failure, incident #" + event.incidentId());

                ReassignmentLog logEntry = new ReassignmentLog(
                        event.incidentId(),
                        booking.id(),
                        event.assetId(),
                        null,
                        ReassignmentOutcome.FORCE_CANCELLED,
                        null,
                        LocalDateTime.now(clock));
                reassignmentLogRepository.save(logEntry);

                sendNotificationSafely(
                        booking.userId(),
                        "Reservation Cancelled",
                        "Your reservation #" + booking.id()
                                + " has been cancelled due to equipment failure (incident #"
                                + event.incidentId() + ").");
            }
        }
    }

    private void sendNotificationSafely(Long userId, String subject, String body) {
        try {
            notificationService.notifyUser(userId, subject, body);
        } catch (Exception e) {
            log.error(
                    "Failed to deliver notification to user {} for subject '{}': {}", userId, subject, e.getMessage());
        }
    }
}

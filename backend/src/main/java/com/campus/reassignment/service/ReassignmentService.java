package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.paging.PageResponse;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reassignment.service.optimal.OptimalReassignmentSolver;
import com.campus.reassignment.web.OptimalReassignmentPlanResponse;
import com.campus.reassignment.web.ReassignmentActionItem;
import com.campus.reassignment.web.ReassignmentLogResponse;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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
    private final ReassignmentBookingProcessor bookingProcessor;
    private final ReassignmentLogRepository reassignmentLogRepository;
    private final OptimalReassignmentSolver optimalReassignmentSolver;
    private final Clock clock;

    /**
     * Handles an {@link AssetDamagedEvent} by iterating over affected upcoming reservations.
     *
     * <p>Bug fixed: each reservation state transition now runs in its own isolated {@code REQUIRES_NEW}
     * transaction via {@link ReassignmentBookingProcessor}. An optimistic-lock conflict or concurrent
     * cancellation on one booking skips that item without rolling back other moved bookings or previously
     * persisted log entries, and notifications are only dispatched after successful transaction commit.
     */
    public void handle(AssetDamagedEvent event) {
        AssetSummary damaged = catalogApi.getAsset(event.assetId());
        LocalDateTime now = now();
        List<ReservationSummary> upcoming = reservationApi.findUpcomingActiveByAsset(event.assetId(), now);

        List<ReservationSummary> sortedBookings = upcoming.stream()
                .sorted(Comparator.comparing(ReservationSummary::start))
                .toList();

        for (ReservationSummary booking : sortedBookings) {
            try {
                Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                        bookingProcessor.processSingleBooking(event, booking, damaged);

                if (resultOpt.isPresent()) {
                    ReassignmentBookingProcessor.ReassignmentExecutionResult result = resultOpt.get();
                    if (result.outcome() == ReassignmentOutcome.REASSIGNED) {
                        sendNotificationSafely(
                                booking.userId(),
                                "Reservation Reassigned",
                                "Your reservation #" + booking.id() + " has been reassigned to asset "
                                        + result.substituteAssetName() + " due to equipment maintenance.");
                    } else {
                        // The only other outcome is FORCE_CANCELLED.
                        sendNotificationSafely(
                                booking.userId(),
                                "Reservation Cancelled",
                                "Your reservation #" + booking.id()
                                        + " has been cancelled due to equipment failure (incident #"
                                        + event.incidentId() + ").");
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Skipping reassignment for booking #{} due to concurrent update or failure", booking.id(), e);
            }
        }
    }

    // Bug fixed (#123): whole seconds, like the contract format (2026-11-03T14:00:00), so timestamps
    // in logs and responses do not drift with subsecond differences.
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private void sendNotificationSafely(Long userId, String subject, String body) {
        try {
            notificationService.notifyUser(userId, subject, body);
        } catch (Exception e) {
            log.error(
                    "Failed to deliver notification to user {} for subject '{}': {}", userId, subject, e.getMessage());
        }
    }

    /**
     * Retrieves a paginated history of reassignment logs for administrator review, newest first.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReassignmentLogResponse> getLogs(int page, int size) {
        if (page < 0) {
            throw new InvalidParameterException("Page index must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new InvalidParameterException("Page size must be between 1 and 100");
        }
        // Bug fixed (#150): prevents integer overflow when Hibernate casts pageable.getOffset() to int.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidParameterException("Page offset exceeds maximum supported value");
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<ReassignmentLog> logPage = reassignmentLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(logPage, ReassignmentLogResponse::from);
    }

    /**
     * Computes the global optimal reassignment plan using Min-Cost Max-Flow (MCMF) for all upcoming
     * reservations of a damaged asset, with optional dry-run simulation.
     */
    public OptimalReassignmentPlanResponse solveOptimalPlan(Long damagedAssetId, Long incidentId, boolean dryRun) {
        AssetSummary damaged = catalogApi.getAsset(damagedAssetId);
        LocalDateTime now = now();
        List<ReservationSummary> upcoming = reservationApi.findUpcomingActiveByAsset(damagedAssetId, now);

        // Gather all candidate substitute assets from the same model and same category
        List<AssetSummary> sameModel = catalogApi.findAvailableByModel(damaged.model(), damagedAssetId);
        List<AssetSummary> sameCategory = catalogApi.findAvailableByCategory(damaged.categoryId(), damagedAssetId);

        Map<Long, AssetSummary> candidatesById = new HashMap<>();
        for (AssetSummary a : sameModel) {
            candidatesById.put(a.id(), a);
        }
        for (AssetSummary a : sameCategory) {
            candidatesById.put(a.id(), a);
        }
        List<AssetSummary> candidateList = new ArrayList<>(candidatesById.values());

        OptimalReassignmentPlanResponse plan = optimalReassignmentSolver.solve(
                damagedAssetId,
                damaged.name(),
                upcoming,
                damaged,
                candidateList,
                (candidateId, booking) ->
                        reservationApi.isAssetFree(candidateId, booking.start(), booking.end(), booking.id()));

        if (dryRun) {
            return plan;
        }

        // Execute optimal actions
        Map<Long, ReservationSummary> bookingMap =
                upcoming.stream().collect(Collectors.toMap(ReservationSummary::id, b -> b));

        int executedRescued = 0;
        int executedCancelled = 0;
        int executedSameModel = 0;
        int executedSameCategory = 0;

        for (ReassignmentActionItem action : plan.actions()) {
            ReservationSummary booking = bookingMap.get(action.reservationId());
            Long studentUserId = booking != null ? booking.userId() : null;

            if (action.assignedAssetId() != null) {
                boolean moved = reservationApi.moveToAsset(
                        action.reservationId(),
                        action.assignedAssetId(),
                        "Optimal Reassignment" + (incidentId != null ? " incident #" + incidentId : ""));
                if (moved) {
                    executedRescued++;
                    if ("SameModel".equals(action.strategyName())) {
                        executedSameModel++;
                    } else {
                        executedSameCategory++;
                    }
                    reassignmentLogRepository.save(new ReassignmentLog(
                            incidentId,
                            action.reservationId(),
                            damagedAssetId,
                            action.assignedAssetId(),
                            ReassignmentOutcome.REASSIGNED,
                            action.strategyName(),
                            now()));
                    if (studentUserId != null) {
                        sendNotificationSafely(
                                studentUserId,
                                "Equipment Reassigned",
                                "Your reservation #" + action.reservationId() + " has been reassigned to asset #"
                                        + action.assignedAssetId() + " (" + action.assignedAssetName() + ") via "
                                        + action.strategyName() + ".");
                    }
                }
            } else {
                boolean cancelled = reservationApi.forceCancel(
                        action.reservationId(),
                        "Optimal Reassignment: no viable substitute"
                                + (incidentId != null ? " (incident #" + incidentId + ")" : ""));
                if (cancelled) {
                    executedCancelled++;
                    reassignmentLogRepository.save(new ReassignmentLog(
                            incidentId,
                            action.reservationId(),
                            damagedAssetId,
                            null,
                            ReassignmentOutcome.FORCE_CANCELLED,
                            null,
                            now()));
                    if (studentUserId != null) {
                        sendNotificationSafely(
                                studentUserId,
                                "Reservation Cancelled",
                                "Your reservation #" + action.reservationId()
                                        + " has been force-cancelled due to equipment maintenance (no viable substitute available).");
                    }
                }
            }
        }

        return new OptimalReassignmentPlanResponse(
                plan.damagedAssetId(),
                plan.damagedAssetName(),
                plan.totalReservations(),
                executedRescued,
                executedCancelled,
                executedSameModel,
                executedSameCategory,
                true,
                plan.actions(),
                plan.comparison());
    }
}

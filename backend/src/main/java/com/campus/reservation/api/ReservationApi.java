package com.campus.reservation.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Public contract for reservation queries and mutations accessed across modules.
 */
public interface ReservationApi {

    /**
     * Finds active reservations (CONFIRMED, CHECKED_OUT, OVERDUE) scheduled on or after {@code from}
     * for the given asset.
     *
     * @param assetId the ID of the asset
     * @param from the start threshold timestamp
     * @return list of active reservation summaries
     */
    List<ReservationSummary> findUpcomingActiveByAsset(Long assetId, LocalDateTime from);

    /**
     * Checks if the asset is free for booking between {@code start} and {@code end}, ignoring a
     * specific reservation (e.g. when checking if the substitute can accommodate a moved booking).
     *
     * @param assetId the ID of the asset to check
     * @param start start of the required time slot
     * @param end end of the required time slot
     * @param ignoreReservationId reservation ID to exclude from conflict checks (nullable)
     * @return true if the asset has no overlapping active reservations
     */
    boolean isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId);

    /**
     * Reassigns an existing confirmed reservation to a new substitute asset.
     *
     * @param reservationId the ID of the reservation to move
     * @param newAssetId the target substitute asset ID
     * @param reason the reason for reassignment (e.g., incident report ID or note)
     */
    void moveToAsset(Long reservationId, Long newAssetId, String reason);

    /**
     * Force-cancels a reservation (e.g., during reassignment when no substitute asset is free).
     * Idempotent if already force-cancelled.
     *
     * @param reservationId the ID of the reservation to force-cancel
     * @param reason the cancellation explanation
     */
    void forceCancel(Long reservationId, String reason);

    /**
     * Finds the active check-out summary for an asset if currently checked out or overdue.
     *
     * @param assetId the ID of the asset
     * @return optional containing the active reservation summary if found
     */
    Optional<ReservationSummary> findCurrentCheckout(Long assetId);
}

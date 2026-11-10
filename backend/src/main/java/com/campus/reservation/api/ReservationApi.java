package com.campus.reservation.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public contract for reservation queries and mutations accessed across modules.
 */
public interface ReservationApi {

    /**
     * Finds CONFIRMED reservations ending after {@code from} for the given asset, ordered by start time
     * (includes slots whose start time has already passed if not yet checked out; equipment already
     * checked out by a student cannot be moved).
     *
     * @param assetId the ID of the asset
     * @param from the cutoff timestamp (reservations with {@code endTime > from} are returned)
     * @return list of confirmed reservation summaries
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
     * @return {@code true} if the reservation was moved, or {@code false} if it was no longer CONFIRMED
     */
    boolean moveToAsset(Long reservationId, Long newAssetId, String reason);

    /**
     * Force-cancels a reservation (e.g., during reassignment when no substitute asset is free).
     * Idempotent if already cancelled or no longer CONFIRMED.
     *
     * @param reservationId the ID of the reservation to force-cancel
     * @param reason the cancellation explanation
     * @return {@code true} if the reservation was force-cancelled, or {@code false} if it was no longer CONFIRMED
     */
    boolean forceCancel(Long reservationId, String reason);

    /**
     * Finds the active check-out summary for an asset if currently checked out or overdue.
     *
     * @param assetId the ID of the asset
     * @return optional containing the active reservation summary if found
     */
    Optional<ReservationSummary> findCurrentCheckout(Long assetId);

    /**
     * Atomically books a kit of 1 to 5 assets for the given time slot (all-or-nothing).
     * Used in-process by the project assistant engine (#140) and other cross-module workflows.
     *
     * <p>Deadlock prevention & concurrency order: Asset rows are sorted in ascending ID order
     * before acquiring pessimistic row locks.
     *
     * @param userId the user ID on whose behalf the bundle is booked
     * @param assetIds the list of asset IDs to reserve (1 to 5 items)
     * @param start start of the reservation time slot
     * @param end end of the reservation time slot
     * @return the unique bundle identifier assigned to all reservations in the kit
     */
    UUID bookBundle(Long userId, List<Long> assetIds, LocalDateTime start, LocalDateTime end);

    /**
     * Cancels all upcoming CONFIRMED reservations for a specific user (e.g. when an administrator
     * disables their account or revokes lab privileges; #229 Part 2).
     *
     * <p>Also automatically cancels any pending outgoing transfer offers for those reservations so they
     * do not remain orphaned.
     *
     * <p>Reservations already checked out (CHECKED_OUT or OVERDUE) are not modified, as the physical
     * equipment is already in the student's possession and must be physically returned at the lab desk.
     *
     * @param userId the ID of the user whose upcoming bookings should be cancelled
     * @param reason explanation for cancellation (e.g. "User account disabled by administrator")
     * @return the number of upcoming reservations that were cancelled
     */
    int cancelUpcomingReservationsForUser(Long userId, String reason);
}

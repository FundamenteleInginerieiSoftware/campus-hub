package com.campus.reservation.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
     * Reassigns an existing confirmed reservation to a new substitute asset only if it is still on
     * {@code expectedSourceAssetId} (when non-null). Acquires a row lock on the reservation first
     * so concurrent incident/retire handlers cannot move the same booking twice (#319).
     *
     * @param reservationId the ID of the reservation to move
     * @param expectedSourceAssetId the asset ID the booking must currently belong to (nullable to skip check)
     * @param newAssetId the target substitute asset ID
     * @param reason the reason for reassignment (e.g., incident report ID or note)
     * @return {@code true} if the reservation was moved, or {@code false} if it was no longer CONFIRMED
     *     or no longer on {@code expectedSourceAssetId}
     */
    boolean moveToAsset(Long reservationId, Long expectedSourceAssetId, Long newAssetId, String reason);

    /**
     * Force-cancels a confirmed reservation only if it is still on {@code expectedSourceAssetId}
     * (when non-null), preventing a stale concurrent handler from cancelling a booking that another
     * handler already moved to a healthy substitute (#319).
     *
     * @param reservationId the ID of the reservation to force-cancel
     * @param expectedSourceAssetId the asset ID the booking must currently belong to (nullable to skip check)
     * @param reason the cancellation explanation
     * @return {@code true} if the reservation was force-cancelled, or {@code false} if it was no longer CONFIRMED
     *     or no longer on {@code expectedSourceAssetId}
     */
    boolean forceCancel(Long reservationId, Long expectedSourceAssetId, String reason);

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
     * <p>Active 1-minute slot holds expire on their own, and incoming transfer offers where the disabled
     * user is the recipient remain {@code PENDING} (accepting is blocked by the disabled account).
     *
     * <p>Reservations already checked out (CHECKED_OUT or OVERDUE) are not modified, as the physical
     * equipment is already in the student's possession and must be physically returned at the lab desk.
     *
     * @param userId the ID of the user whose upcoming bookings should be cancelled
     * @param reason explanation for cancellation (max 255 characters; e.g. "Account disabled by administrator")
     * @return the number of upcoming reservations that were cancelled
     */
    int cancelUpcomingReservationsForUser(Long userId, String reason);

    /**
     * Computes real equipment usage minutes per asset within the {@code [from, to]} window.
     *
     * <p>Counts only reservations that were actually checked out ({@code checkedOutAt != null}),
     * measuring from {@code checkedOutAt} to {@code returnedAt}, or to {@code min(now, to)} if the
     * asset is still {@code CHECKED_OUT} or {@code OVERDUE}, clipped to {@code [from, to]}. Bookings
     * that were never picked up ({@code CONFIRMED}, {@code CANCELLED}, {@code FORCE_CANCELLED},
     * {@code NO_SHOW}) do not count.
     *
     * @param from start of the measurement window (non-null)
     * @param to end of the measurement window (non-null)
     * @return map from asset ID to total positive usage minutes in the window (empty if {@code to <= from})
     */
    Map<Long, Long> usedMinutesByAsset(LocalDateTime from, LocalDateTime to);
}

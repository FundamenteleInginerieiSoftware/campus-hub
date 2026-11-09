package com.campus.reservation.web;

/**
 * Aggregate counts for a student's reservations, backing the dashboard overview cards.
 *
 * @param activeCount active booking sessions (CONFIRMED, CHECKED_OUT, OVERDUE), where multi-asset bundle bookings share
 *     one bundleId and count as 1 active slot towards the student limit (max 2).
 * @param completedCount completed bookings (RETURNED)
 * @param reassignedCount bookings where originalAssetId is not null (moved by engine)
 */
public record ReservationSummaryResponse(long activeCount, long completedCount, long reassignedCount) {}

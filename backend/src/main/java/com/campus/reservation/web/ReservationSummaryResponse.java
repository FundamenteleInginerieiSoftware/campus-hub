package com.campus.reservation.web;

/**
 * Aggregate counts for a student's reservations, backing the dashboard overview cards.
 *
 * @param activeCount active bookings (CONFIRMED, CHECKED_OUT, OVERDUE)
 * @param completedCount completed bookings (RETURNED)
 * @param reassignedCount bookings where originalAssetId is not null (moved by engine)
 */
public record ReservationSummaryResponse(long activeCount, long completedCount, long reassignedCount) {}

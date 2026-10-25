package com.campus.reservation.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Status of a reservation through its lifecycle.
 */
public enum ReservationStatus {
    CONFIRMED,
    CHECKED_OUT,
    RETURNED,
    CANCELLED,
    FORCE_CANCELLED,
    OVERDUE,
    NO_SHOW;

    public static final Set<ReservationStatus> ACTIVE_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(CONFIRMED, CHECKED_OUT, OVERDUE));
}

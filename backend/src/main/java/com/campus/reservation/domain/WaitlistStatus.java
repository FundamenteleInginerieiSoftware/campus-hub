package com.campus.reservation.domain;

/**
 * Lifecycle status of a student's waitlist request for an occupied equipment slot.
 */
public enum WaitlistStatus {
    WAITING,
    PROMOTED,
    CANCELLED,
    SKIPPED_QUOTA_FULL,
    EXPIRED
}

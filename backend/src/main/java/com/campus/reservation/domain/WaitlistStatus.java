package com.campus.reservation.domain;

/**
 * Lifecycle state of a student's FIFO waitlist entry for an occupied equipment slot (#59).
 */
public enum WaitlistStatus {
    WAITING,
    PROMOTED,
    CANCELLED,
    EXPIRED,
    SKIPPED_QUOTA_FULL
}

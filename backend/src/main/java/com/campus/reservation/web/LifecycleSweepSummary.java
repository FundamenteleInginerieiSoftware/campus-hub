package com.campus.reservation.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary of an automated reservation lifecycle sweep (no-show release, overdue detection, and
 * preventive cascade reassignments).
 */
public record LifecycleSweepSummary(
        LocalDateTime executedAt,
        int noShowReleasedCount,
        int markedOverdueCount,
        int preventivelyReassignedCount,
        List<Long> preventivelyReassignedReservationIds) {}

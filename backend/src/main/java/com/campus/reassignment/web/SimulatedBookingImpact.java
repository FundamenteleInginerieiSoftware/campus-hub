package com.campus.reassignment.web;

import com.campus.reassignment.domain.ReassignmentOutcome;
import java.time.LocalDateTime;

public record SimulatedBookingImpact(
        Long reservationId,
        Long userId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        ReassignmentOutcome outcome,
        Long substituteAssetId,
        String substituteAssetName,
        String strategy) {}

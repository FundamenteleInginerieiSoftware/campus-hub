package com.campus.reassignment.web;

import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import java.time.LocalDateTime;

public record ReassignmentLogResponse(
        Long id,
        Long incidentId,
        Long reservationId,
        Long fromAssetId,
        Long toAssetId,
        ReassignmentOutcome outcome,
        String strategy,
        LocalDateTime createdAt) {

    public static ReassignmentLogResponse from(ReassignmentLog log) {
        return new ReassignmentLogResponse(
                log.getId(),
                log.getIncidentId(),
                log.getReservationId(),
                log.getFromAssetId(),
                log.getToAssetId(),
                log.getOutcome(),
                log.getStrategy(),
                log.getCreatedAt());
    }
}

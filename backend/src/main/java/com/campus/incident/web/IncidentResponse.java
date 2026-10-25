package com.campus.incident.web;

import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import java.time.Instant;

public record IncidentResponse(
        Long id,
        Long assetId,
        Long reservationId,
        Long reportedById,
        String description,
        IncidentSeverity severity,
        IncidentStatus status,
        String photoKey,
        Instant createdAt,
        Instant resolvedAt,
        Long resolvedById,
        String resolutionNotes) {}

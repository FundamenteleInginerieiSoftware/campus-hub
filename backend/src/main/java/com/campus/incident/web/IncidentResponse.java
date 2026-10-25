package com.campus.incident.web;

import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.domain.IncidentReport.Severity;
import java.time.LocalDateTime;

public record IncidentResponse(
        Long id,
        Long assetId,
        Long reportedById,
        Long reservationId,
        String description,
        Severity severity,
        String photoKey,
        IncidentStatus status,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt,
        Long resolvedById,
        String resolutionNote) {
    public static IncidentResponse fromEntity(IncidentReport entity) {
        return new IncidentResponse(
                entity.getId(),
                entity.getAssetId(),
                entity.getReportedById(),
                entity.getReservationId(),
                entity.getDescription(),
                entity.getSeverity(),
                entity.getPhotoKey(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getResolvedAt(),
                entity.getResolvedById(),
                entity.getResolutionNote());
    }
}

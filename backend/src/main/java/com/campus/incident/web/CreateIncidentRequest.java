package com.campus.incident.web;

import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
        @NotNull(message = "assetId is required") Long assetId,
        Long reservationId,

        @NotBlank(message = "description is required")
        @Size(max = 2000, message = "description cannot exceed 2000 characters")
        String description,

        @NotNull(message = "severity is required") IncidentSeverity severity,
        String photoKey) {}

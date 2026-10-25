package com.campus.incident.web;

import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import jakarta.validation.constraints.NotNull;

public record CreateIncidentRequest(
        @NotNull(message = "Asset ID is required") Long assetId,
        @NotNull(message = "Description is required") String description,
        @NotNull(message = "Severity is required") IncidentSeverity severity) {}

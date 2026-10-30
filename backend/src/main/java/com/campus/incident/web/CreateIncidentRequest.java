package com.campus.incident.web;

import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
        @NotNull(message = "Asset ID is required") Long assetId,

        @NotBlank(message = "Description is required")
        @Size(max = 2000, message = "Description cannot exceed 2000 characters")
        String description,

        @NotNull(message = "Severity is required") IncidentSeverity severity) {}

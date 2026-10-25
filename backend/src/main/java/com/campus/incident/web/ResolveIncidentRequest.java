package com.campus.incident.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload for resolving an incident")
public record ResolveIncidentRequest(
        @Schema(
                description = "Optional resolution details or notes from maintenance",
                example = "Replaced damaged HDMI cable and verified projector functionality")
        @Size(max = 2000, message = "Resolution notes cannot exceed 2000 characters")
        String resolutionNotes) {}

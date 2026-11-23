package com.campus.reassignment.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record SimulateMaintenanceRequest(
        @NotNull(message = "Asset ID is required")
        @Schema(description = "ID of the equipment asset being taken offline for maintenance", example = "1")
        Long assetId,

        @NotNull(message = "Maintenance window start is required")
        @Schema(
                description = "Maintenance window start in campus local time (no timezone offset)",
                example = "2026-11-10T09:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime windowStart,

        @NotNull(message = "Maintenance window end is required")
        @Schema(
                description = "Maintenance window end in campus local time (no timezone offset)",
                example = "2026-11-10T17:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime windowEnd) {}

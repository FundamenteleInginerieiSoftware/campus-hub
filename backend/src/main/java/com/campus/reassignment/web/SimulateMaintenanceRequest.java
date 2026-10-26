package com.campus.reassignment.web;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Request payload for simulating the reassignment impact of taking an asset offline for planned maintenance.
 */
public record SimulateMaintenanceRequest(
        @NotNull(message = "Asset ID is required") Long assetId,

        @NotNull(message = "Maintenance start time is required")
        LocalDateTime maintenanceStart,

        @NotNull(message = "Maintenance end time is required")
        LocalDateTime maintenanceEnd) {}

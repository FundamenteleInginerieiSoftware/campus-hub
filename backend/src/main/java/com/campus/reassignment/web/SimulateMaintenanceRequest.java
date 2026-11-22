package com.campus.reassignment.web;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record SimulateMaintenanceRequest(
        @NotNull Long assetId,
        @NotNull LocalDateTime windowStart,
        @NotNull LocalDateTime windowEnd) {}

package com.campus.reservation.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Operational telemetry for PostgreSQL deadlock and transient fault retries")
public record RetryMetricsResponse(
        @Schema(description = "Total number of retry attempts executed", example = "14")
        long totalRetries,

        @Schema(description = "Total number of operations that succeeded after one or more retries", example = "6")
        long successfulRecoveries,

        @Schema(description = "Total number of operations where all retries were exhausted", example = "0")
        long exhaustedRetries) {}

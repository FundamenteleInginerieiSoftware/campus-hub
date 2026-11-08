package com.campus.reservation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Request payload to create a recurring reservation series on specific days of the week.
 */
@Schema(description = "Request to create a recurring reservation series")
public record CreateRecurringReservationRequest(
        @NotNull(message = "assetId must not be null")
        @Schema(description = "ID of the asset to reserve", example = "1")
        Long assetId,

        @NotNull(message = "startTime must not be null")
        @Schema(description = "Start time of day for each recurrence slot", example = "14:00:00")
        LocalTime startTime,

        @NotNull(message = "endTime must not be null")
        @Schema(description = "End time of day for each recurrence slot", example = "16:00:00")
        LocalTime endTime,

        @NotEmpty(message = "daysOfWeek must not be empty")
        @Schema(description = "Days of the week on which the booking repeats", example = "[\"MONDAY\", \"WEDNESDAY\"]")
        Set<DayOfWeek> daysOfWeek,

        @NotNull(message = "startDate must not be null")
        @Schema(description = "Starting date of the recurrence series", example = "2026-11-09")
        LocalDate startDate,

        @NotNull(message = "endDate must not be null")
        @Schema(description = "Ending date of the recurrence series (inclusive)", example = "2026-11-30")
        LocalDate endDate) {}

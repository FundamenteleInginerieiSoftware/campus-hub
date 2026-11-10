package com.campus.reservation.web;

import com.campus.reservation.service.ReservationAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin analytics controller providing laboratory equipment utilization statistics,
 * capacity bottleneck alerts, and weekly booking demand heatmaps.
 *
 * <p>Protected under {@code /api/v1/admin/**} by SecurityConfig requiring ADMIN role.
 */
@Tag(
        name = "Admin Analytics",
        description = "Equipment utilization analytics, bottleneck identification, and booking demand heatmap")
@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final ReservationAnalyticsService analyticsService;

    @Operation(
            summary = "Equipment utilization and capacity analytics (Admin)",
            description =
                    "Calculates aggregate laboratory utilization, cancellation/no-show rates, per-equipment utilization items, and detects model capacity bottlenecks.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Utilization metrics successfully calculated",
                content = @Content(schema = @Schema(implementation = UtilizationAnalyticsResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid date range parameters (from is after to, window longer than 366 days, or a timezone offset)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication token missing or invalid",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Admin role required",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/utilization")
    public ResponseEntity<UtilizationAnalyticsResponse> getUtilization(
            @Parameter(
                            description = "Start timestamp (ISO-8601 local time without offset, inclusive)",
                            example = "2026-10-15T08:00:00")
                    @RequestParam(required = false)
                    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
                    LocalDateTime from,
            @Parameter(
                            description = "End timestamp (ISO-8601 local time without offset, inclusive)",
                            example = "2026-11-15T20:00:00")
                    @RequestParam(required = false)
                    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
                    LocalDateTime to) {
        return ResponseEntity.ok(analyticsService.getUtilization(from, to));
    }

    @Operation(
            summary = "Booking demand heatmap (Admin)",
            description =
                    "Generates a 7-day by 12-hour demand intensity grid (08:00-20:00) across all equipment or filtered by asset ID.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Demand heatmap grid successfully calculated",
                content = @Content(schema = @Schema(implementation = HeatmapResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid date range parameters (from is after to, window longer than 366 days, or a timezone offset)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication token missing or invalid",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Admin role required",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/heatmap")
    public ResponseEntity<HeatmapResponse> getHeatmap(
            @Parameter(
                            description = "Start timestamp (ISO-8601 local time without offset, inclusive)",
                            example = "2026-10-15T08:00:00")
                    @RequestParam(required = false)
                    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
                    LocalDateTime from,
            @Parameter(
                            description = "End timestamp (ISO-8601 local time without offset, inclusive)",
                            example = "2026-11-15T20:00:00")
                    @RequestParam(required = false)
                    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
                    LocalDateTime to,
            @Parameter(description = "Optional asset ID to filter demand for a specific equipment unit")
                    @RequestParam(required = false)
                    Long assetId) {
        return ResponseEntity.ok(analyticsService.getHeatmap(from, to, assetId));
    }
}

package com.campus.reservation.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Aggregated 7x12 weekly demand heatmap showing booking distribution across days and working hours (08:00 to 20:00).
 *
 * @param from start of the analyzed period
 * @param to end of the analyzed period
 * @param assetId optional asset ID filter, or null if showing lab-wide demand
 * @param totalBookingsSampled number of active bookings contributing to the heatmap
 * @param peakHourlyDemand maximum concurrent bookings observed in a single day/hour cell
 * @param cells complete list of 84 grid cells (7 days * 12 hours) ordered by dayOfWeek asc, hour asc
 */
public record HeatmapResponse(
        LocalDateTime from,
        LocalDateTime to,
        Long assetId,
        int totalBookingsSampled,
        int peakHourlyDemand,
        List<HeatmapCell> cells) {}

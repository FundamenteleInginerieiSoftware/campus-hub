package com.campus.reservation.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * High-level laboratory equipment utilization and capacity analytics report.
 *
 * @param from start of the analyzed time range (inclusive)
 * @param to end of the analyzed time range (inclusive)
 * @param reportingDays number of calendar days in the analyzed range
 * @param totalBookedHours sum of booked hours across all equipment in the period
 * @param totalCapacityHours maximum possible equipment hours (reportingDays * 12h/day * totalAssetsTracked)
 * @param averageUtilizationPercent aggregate laboratory utilization percentage
 * @param totalBookingsCount total number of bookings (realized, cancelled, no-show) in the period
 * @param cancellationRate percentage of bookings that were cancelled
 * @param noShowRate percentage of bookings marked as no-show
 * @param totalAssetsTracked number of distinct equipment items tracked
 * @param idleAssetsCount number of equipment items with 0 booked hours in the period
 * @param assets detailed per-equipment utilization items sorted descending by utilization
 * @param bottlenecks capacity bottlenecks detected where concurrent demand saturated equipment models
 */
public record UtilizationAnalyticsResponse(
        LocalDateTime from,
        LocalDateTime to,
        int reportingDays,
        double totalBookedHours,
        double totalCapacityHours,
        double averageUtilizationPercent,
        int totalBookingsCount,
        double cancellationRate,
        double noShowRate,
        int totalAssetsTracked,
        int idleAssetsCount,
        List<AssetUtilizationItem> assets,
        List<CapacityBottleneckItem> bottlenecks) {}

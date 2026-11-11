package com.campus.reservation.web;

import com.campus.catalog.api.AssetStatus;

/**
 * Utilization statistics for a single piece of equipment over a reporting period.
 *
 * @param assetId primary key of the asset
 * @param assetName human-readable name of the equipment
 * @param model hardware model designation
 * @param categoryId category identifier
 * @param bookedHours total hours booked in realized statuses (CONFIRMED, CHECKED_OUT, OVERDUE, RETURNED)
 * @param utilizationPercent percentage of lab open hours (12h/day) utilized by this equipment
 * @param bookingCount number of bookings intersecting the reporting period
 * @param status operational status in the catalog (AVAILABLE, UNDER_MAINTENANCE, RETIRED)
 * @param idle true if the asset had zero booked hours during the reporting period
 */
public record AssetUtilizationItem(
        Long assetId,
        String assetName,
        String model,
        Long categoryId,
        double bookedHours,
        double utilizationPercent,
        int bookingCount,
        AssetStatus status,
        boolean idle) {}

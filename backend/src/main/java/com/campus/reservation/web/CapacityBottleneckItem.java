package com.campus.reservation.web;

/**
 * Capacity bottleneck alert indicating an equipment model that frequently reaches full concurrent capacity.
 *
 * @param model hardware model designation
 * @param totalUnits total tracked units of this model in the catalog
 * @param peakConcurrentDemand maximum concurrent active bookings observed for this model
 * @param bottleneckHoursCount number of hours where simultaneous demand reached 100% of available units
 * @param peakUtilizationRate ratio of peak demand to available units
 * @param recommendation actionable resource recommendation for lab administrators
 */
public record CapacityBottleneckItem(
        String model,
        int totalUnits,
        int peakConcurrentDemand,
        int bottleneckHoursCount,
        double peakUtilizationRate,
        String recommendation) {}

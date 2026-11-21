package com.campus.incident.web;

import java.math.BigDecimal;

/**
 * One row of the equipment reliability report. Every number has one decimal.
 *
 * @param mtbfHours hours of use per failure; {@code null} when the unit was never used
 * @param avgRepairHours average time from report to resolve; {@code null} when nothing was resolved
 * @param vsModel how many times more often the unit fails than the other units of its model;
 *     {@code null} when its twins have less than 10 hours of use together or never failed
 */
public record AssetReliabilityItem(
        Long assetId,
        String assetName,
        String model,
        String status,
        int failures,
        int severeFailures,
        BigDecimal usageHours,
        BigDecimal failuresPer100h,
        BigDecimal mtbfHours,
        BigDecimal avgRepairHours,
        BigDecimal vsModel,
        ReliabilityLevel level,
        String reason) {}

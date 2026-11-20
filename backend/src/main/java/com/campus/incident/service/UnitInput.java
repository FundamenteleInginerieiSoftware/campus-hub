package com.campus.incident.service;

/**
 * One unit the reliability report looks at, already looked up in the catalog.
 *
 * @param usedMinutes minutes the unit was really out in the window (check-out to return), from
 *     {@code ReservationApi.usedMinutesByAsset} (Calin, #298); {@code 0} when it was never checked out
 */
public record UnitInput(Long assetId, String name, String model, String status, long usedMinutes) {}

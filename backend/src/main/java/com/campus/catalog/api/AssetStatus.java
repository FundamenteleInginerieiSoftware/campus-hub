package com.campus.catalog.api;

/**
 * Operational status of a lab asset.
 * Availability for a specific time slot is computed from reservations, never stored here.
 */
public enum AssetStatus {
    AVAILABLE,
    UNDER_MAINTENANCE,
    RETIRED
}

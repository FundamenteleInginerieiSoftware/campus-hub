package com.campus.incident.api;

import java.time.Instant;

/**
 * Domain event published when an incident report damages an asset and requires reassignment.
 */
public record AssetDamagedEvent(Long incidentId, Long assetId, Long reportedById, Instant occurredAt) {}

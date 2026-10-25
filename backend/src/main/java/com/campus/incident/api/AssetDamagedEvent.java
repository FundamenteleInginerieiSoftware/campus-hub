package com.campus.incident.api;

import java.time.LocalDateTime;

/**
 * Event published when an asset is reported as damaged or unusable.
 * Consumed by the reassignment module to rebook affected reservations.
 */
public record AssetDamagedEvent(Long incidentId, Long assetId, Long reportedById, LocalDateTime reportedAt) {}

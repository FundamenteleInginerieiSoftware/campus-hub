package com.campus.incident.api;

import java.time.Instant;

public record AssetDamagedEvent(Long incidentId, Long assetId, Long reportedById, Instant occurredAt) {}

package com.campus.incident.api;

import java.time.LocalDateTime;

public record AssetRepairedEvent(Long incidentId, Long assetId, Long resolvedById, LocalDateTime resolvedAt) {}

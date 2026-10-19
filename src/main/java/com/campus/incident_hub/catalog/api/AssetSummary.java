package com.campus.incident_hub.catalog.api;

import com.campus.incident_hub.catalog.AssetStatus;

public record AssetSummary(
        Long id,
        String name,
        String model,
        String serialNumber,
        AssetStatus status,
        String categoryName,
        String labRoomCode
) {
}

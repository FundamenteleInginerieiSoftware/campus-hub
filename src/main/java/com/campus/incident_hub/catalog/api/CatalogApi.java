package com.campus.incident_hub.catalog.api;

import java.util.Optional;
import java.util.List;

public interface CatalogApi {
    Optional<AssetSummary> getAsset(Long id);
    boolean isBookable(Long id);
    void lockForBooking(Long id);
    void markUnderMaintenance(Long id);
    void markAvailable(Long id);
    List<AssetSummary> findAvailableByModel(String model, Long excludeId);
    List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeId);
}

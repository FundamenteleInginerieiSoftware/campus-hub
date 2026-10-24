package com.campus.catalog.api;

import java.util.List;

/**
 * Public facade of the catalog module.
 * Other modules (reservation, incident, reassignment) must interact with catalog data exclusively
 * through this interface.
 */
public interface CatalogApi {

    /**
     * Returns the summary of an asset by ID, or throws {@code NotFoundException} ("ASSET_NOT_FOUND")
     * if it does not exist.
     */
    AssetSummary getAsset(Long assetId);

    /**
     * Returns {@code true} if the asset exists and its operational status is {@link AssetStatus#AVAILABLE}.
     */
    boolean isBookable(Long assetId);

    /**
     * Finds all {@link AssetStatus#AVAILABLE} assets sharing the same model, excluding the damaged asset.
     * Used by Calin's {@code SameModelStrategy} during automatic reassignment.
     */
    List<AssetSummary> findAvailableByModel(String model, Long excludeAssetId);

    /**
     * Finds all {@link AssetStatus#AVAILABLE} assets in the given category, excluding the damaged asset.
     * Used by Calin's {@code SameCategoryStrategy} when no same-model substitute is free.
     */
    List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeAssetId);

    /**
     * Marks an asset as {@link AssetStatus#UNDER_MAINTENANCE}. Idempotent if already under maintenance.
     * Called when an incident is reported for the asset.
     */
    void markUnderMaintenance(Long assetId);

    /**
     * Marks an asset back as {@link AssetStatus#AVAILABLE}. Idempotent if already available.
     * Called when an admin resolves the incident and repairs the asset.
     */
    void markAvailable(Long assetId);

    /**
     * Acquires a pessimistic database row lock ({@code SELECT ... FOR UPDATE}) on the asset.
     * Must be called inside the caller's {@code @Transactional} booking method before checking
     * overlapping reservations so two simultaneous requests cannot double-book the same asset.
     */
    void lockForBooking(Long assetId);
}

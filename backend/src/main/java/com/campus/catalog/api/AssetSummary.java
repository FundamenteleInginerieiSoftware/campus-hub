package com.campus.catalog.api;

/**
 * Lightweight projection of an asset exposed to other modules (reservation, incident, reassignment)
 * through {@link CatalogApi}.
 */
public record AssetSummary(Long id, String name, String model, Long categoryId, Long labRoomId, AssetStatus status) {}

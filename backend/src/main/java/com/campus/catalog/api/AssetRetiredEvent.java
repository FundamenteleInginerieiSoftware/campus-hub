package com.campus.catalog.api;

import java.time.LocalDateTime;

/**
 * Domain event published when an equipment asset is marked as RETIRED by an administrator.
 *
 * <p>Consumed by the reassignment module (Calin's engine) to reassign upcoming bookings
 * of the decommissioned asset to an available substitute of the same model, or force-cancel
 * them if no replacement is available (#245).
 *
 * @param assetId   the ID of the retired asset
 * @param retiredAt the timestamp when the asset was retired (campus local time)
 * @param actorId   the user ID of the administrator who retired the asset
 */
public record AssetRetiredEvent(Long assetId, LocalDateTime retiredAt, Long actorId) {}

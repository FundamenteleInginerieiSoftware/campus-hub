package com.campus.catalog.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.catalog.domain.Asset;
import com.campus.catalog.repository.AssetRepository;
import com.campus.common.error.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link CatalogApi} so the reservation, incident, and reassignment modules can query
 * and lock assets without touching catalog internals.
 */
@Service
public class CatalogService implements CatalogApi {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");

    private final AssetRepository assetRepository;
    private final Clock clock;

    public CatalogService(AssetRepository assetRepository, ObjectProvider<Clock> clockProvider) {
        this.assetRepository = assetRepository;
        // Uses Calin's shared Clock bean once merged on main; falls back to Europe/Bucharest until then.
        this.clock = clockProvider.getIfAvailable(() -> Clock.system(CAMPUS_ZONE));
    }

    @Override
    @Transactional(readOnly = true)
    public AssetSummary getAsset(Long assetId) {
        Asset asset = assetRepository
                .findById(assetId)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + assetId + " was not found"));
        return toSummary(asset);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBookable(Long assetId) {
        return assetRepository
                .findById(assetId)
                .map(asset -> asset.getStatus() == AssetStatus.AVAILABLE)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetSummary> findAvailableByModel(String model, Long excludeAssetId) {
        return assetRepository.findByModelAndStatusAndIdNot(model, AssetStatus.AVAILABLE, excludeAssetId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeAssetId) {
        return assetRepository
                .findByCategoryIdAndStatusAndIdNot(categoryId, AssetStatus.AVAILABLE, excludeAssetId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional
    public void markUnderMaintenance(Long assetId) {
        Asset asset = assetRepository
                .findByIdForUpdate(assetId)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + assetId + " was not found"));
        asset.markUnderMaintenance(LocalDateTime.now(clock));
    }

    @Override
    @Transactional
    public void markAvailable(Long assetId) {
        Asset asset = assetRepository
                .findByIdForUpdate(assetId)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + assetId + " was not found"));
        asset.markAvailable(LocalDateTime.now(clock));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForBooking(Long assetId) {
        assetRepository
                .findByIdForUpdate(assetId)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + assetId + " was not found"));
    }

    private AssetSummary toSummary(Asset asset) {
        return new AssetSummary(
                asset.getId(),
                asset.getName(),
                asset.getModel(),
                asset.getCategory().getId(),
                asset.getLabRoom().getId(),
                asset.getStatus());
    }
}

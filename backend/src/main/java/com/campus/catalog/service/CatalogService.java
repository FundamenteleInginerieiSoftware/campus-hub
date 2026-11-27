package com.campus.catalog.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.catalog.domain.Asset;
import com.campus.catalog.repository.AssetRepository;
import com.campus.common.error.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link CatalogApi} so the reservation, incident, and reassignment modules can query
 * and lock assets without touching catalog internals.
 */
@Service
@RequiredArgsConstructor
public class CatalogService implements CatalogApi {

    private final AssetRepository assetRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public AssetSummary getAsset(Long assetId) {
        return toSummary(findOrThrow(assetId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AssetSummary> findAssetsByIds(Collection<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        return assetRepository.findAllById(assetIds).stream()
                .map(this::toSummary)
                .collect(Collectors.toMap(AssetSummary::id, Function.identity()));
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
        // Call the non-excluding query when excludeAssetId is null so SQL never evaluates "id <> NULL" (#363).
        List<Asset> assets = excludeAssetId == null
                ? assetRepository.findByModelAndStatus(model, AssetStatus.AVAILABLE)
                : assetRepository.findByModelAndStatusAndIdNot(model, AssetStatus.AVAILABLE, excludeAssetId);
        return assets.stream().map(this::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeAssetId) {
        // Call the non-excluding query when excludeAssetId is null so SQL never evaluates "id <> NULL" (#363).
        List<Asset> assets = excludeAssetId == null
                ? assetRepository.findByCategoryIdAndStatus(categoryId, AssetStatus.AVAILABLE)
                : assetRepository.findByCategoryIdAndStatusAndIdNot(categoryId, AssetStatus.AVAILABLE, excludeAssetId);
        return assets.stream().map(this::toSummary).toList();
    }

    @Override
    @Transactional
    public void markUnderMaintenance(Long assetId) {
        Asset asset = findForUpdateOrThrow(assetId);
        asset.markUnderMaintenance(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    @Override
    @Transactional
    public void markAvailable(Long assetId) {
        Asset asset = findForUpdateOrThrow(assetId);
        asset.markAvailable(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForBooking(Long assetId) {
        findForUpdateOrThrow(assetId);
    }

    private Asset findOrThrow(Long assetId) {
        return assetRepository
                .findById(assetId)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + assetId + " was not found"));
    }

    private Asset findForUpdateOrThrow(Long assetId) {
        return assetRepository
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

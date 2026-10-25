package com.campus.catalog.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * Stub implementation of {@link CatalogApi} used until Liviu's catalog module is merged into main.
 *
 * <p>Uses {@code @ConditionalOnMissingBean} so when Liviu's real {@code CatalogService} lands on
 * main, this stub automatically yields and the real service takes over without conflict.
 */
@Service
@ConditionalOnMissingBean(CatalogApi.class)
class CatalogServiceStub implements CatalogApi {

    @Override
    public AssetSummary getAsset(Long assetId) {
        return new AssetSummary(assetId, "Sample Asset", "Model-X", 1L, 1L, AssetStatus.AVAILABLE);
    }

    @Override
    public boolean isBookable(Long assetId) {
        return true;
    }

    @Override
    public List<AssetSummary> findAvailableByModel(String model, Long excludeAssetId) {
        return List.of();
    }

    @Override
    public List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeAssetId) {
        return List.of();
    }

    @Override
    public void markUnderMaintenance(Long assetId) {}

    @Override
    public void markAvailable(Long assetId) {}

    @Override
    public void lockForBooking(Long assetId) {}
}

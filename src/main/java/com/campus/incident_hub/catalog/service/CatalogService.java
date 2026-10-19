package com.campus.incident_hub.catalog.service;

import com.campus.incident_hub.catalog.Asset;
import com.campus.incident_hub.catalog.AssetRepository;
import com.campus.incident_hub.catalog.AssetStatus;
import com.campus.incident_hub.catalog.api.AssetSummary;
import com.campus.incident_hub.catalog.api.CatalogApi;
import com.campus.incident_hub.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CatalogService implements CatalogApi {


    private final AssetRepository assetRepository;

    private AssetSummary toSummary(Asset asset) {
        return new AssetSummary(
                asset.getId(),
                asset.getName(),
                asset.getModel(),
                asset.getSerialNumber(),
                asset.getStatus(),
                asset.getCategory().getName(),
                asset.getLabRoom().getCode()
        );
    }

    public Optional<AssetSummary> getAsset(Long id) {
        return assetRepository.findById(id)
                .map(this::toSummary);
    }

    @Override
    public boolean isBookable(Long id) {
        return assetRepository.findById(id)
                .map(asset -> asset.getStatus() == AssetStatus.AVAILABLE)
                .orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForBooking(Long id) {

        assetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Echipamentul cu ID-ul " + id + " nu a fost găsit."));

    }

    @Transactional
    public void markUnderMaintenance(Long id) {
        Asset asset = assetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Echipamentul cu ID-ul " + id + " nu a fost găsit."));

        asset.markUnderMaintenance();
        assetRepository.save(asset);
    }

    @Transactional
    public void markAvailable(Long id) {
        Asset asset = assetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Echipamentul cu ID-ul " + id + " nu a fost găsit."));
        asset.markAvailable();
        assetRepository.save(asset);
    }

    public List<AssetSummary> findAvailableByModel(String model, Long excludeId) {
        return assetRepository.findByModelAndStatusAndIdNot(model, AssetStatus.AVAILABLE, excludeId)
                .stream().map(this::toSummary).toList();

    }

    public List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeId) {
        return assetRepository.findByCategoryIdAndStatusAndIdNot(categoryId, AssetStatus.AVAILABLE, excludeId)
                .stream().map(this::toSummary).toList();
    }
}

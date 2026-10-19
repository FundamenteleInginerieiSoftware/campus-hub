package com.campus.incident_hub.catalog;

import com.campus.incident_hub.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;


@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetRepository assetRepository;

    public List<Asset> getAllAssets() {
        return assetRepository.findAll();
    }

    public Asset createAsset(Asset asset) {
        return assetRepository.save(asset);
    }

    public void deleteAsset(Long id) {
        if(!assetRepository.existsById(id)) {
            throw new ResourceNotFoundException("Echipamentul cu ID-ul " + id + " nu poate fi șters deoarece nu există.");
        }
        assetRepository.deleteById(id);
    }

    public Asset getAssetById(Long id) {
        return assetRepository.findById(id)
                .orElseThrow(()-> new ResourceNotFoundException("Echipamentul cu ID-ul " + id + " nu a fost găsit."));

    }
}
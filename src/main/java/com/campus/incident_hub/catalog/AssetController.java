package com.campus.incident_hub.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.campus.incident_hub.common.exception.ResourceNotFoundException;
import java.util.List;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/assets")
@CrossOrigin(origins = "*") // Permite accesul din frontend/React
@RequiredArgsConstructor

public class AssetController {

    private final AssetService assetService;

    @GetMapping
    public List<Asset> getAllAssets() {
        return assetService.getAllAssets();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Asset createAsset(@Valid @RequestBody Asset asset) {
        return assetService.createAsset(asset);
    }

    @DeleteMapping("/{id}")
    public void deleteAsset(@PathVariable Long id) {
        assetService.deleteAsset(id);
    }

    @GetMapping("/{id}")
    public Asset getAssetById(@PathVariable Long id) {
        return assetService.getAssetById(id);
    }
}

package com.campus.catalog.web;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.service.AssetService;
import com.campus.common.paging.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Assets", description = "Equipment catalog search, details, and admin inventory management")
public class AssetController {

    private final AssetService assetService;

    @GetMapping("/api/v1/assets")
    @Operation(summary = "Search and filter equipment with pagination and sorting")
    public PageResponse<AssetResponse> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long labRoomId,
            @RequestParam(required = false) AssetStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return assetService.search(search, categoryId, labRoomId, status, page, size, sort);
    }

    @GetMapping("/api/v1/assets/{id}")
    @Operation(summary = "Get equipment details by ID (404 ASSET_NOT_FOUND)")
    public AssetResponse getById(@PathVariable Long id) {
        return assetService.getById(id);
    }

    @PostMapping("/api/v1/admin/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new equipment item (409 SERIAL_NUMBER_TAKEN, 404 category/room missing)")
    public AssetResponse create(@Valid @RequestBody CreateAssetRequest request) {
        return assetService.create(request);
    }

    @PutMapping("/api/v1/admin/assets/{id}")
    @Operation(summary = "Update equipment details (409 ASSET_RETIRED if already retired)")
    public AssetResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAssetRequest request) {
        return assetService.update(id, request);
    }

    // Assets are never hard-deleted because reservations and incidents reference them.
    @PostMapping("/api/v1/admin/assets/{id}/retire")
    @Operation(summary = "Retire an equipment item instead of deleting it (409 ASSET_RETIRED if already retired)")
    public AssetResponse retire(@PathVariable Long id) {
        return assetService.retire(id);
    }
}

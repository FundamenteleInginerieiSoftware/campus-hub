package com.campus.catalog.web;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.service.AssetService;
import com.campus.common.paging.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

// 401 (and 403 under /admin) are added automatically by OpenApiConfig (#184).
@RestController
@RequiredArgsConstructor
@Tag(name = "Assets", description = "Equipment catalog search, details, and admin inventory management")
public class AssetController {

    private final AssetService assetService;

    @GetMapping("/api/v1/assets")
    @Operation(summary = "Search and filter equipment with pagination and sorting")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated page of equipment assets"),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid filter, pagination, or sort parameter (INVALID_PARAMETER)")
    })
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
    @Operation(summary = "Get equipment details by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Equipment details"),
        @ApiResponse(responseCode = "400", description = "Invalid asset ID parameter (INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "404", description = "Asset not found (ASSET_NOT_FOUND)")
    })
    public AssetResponse getById(@PathVariable Long id) {
        return assetService.getById(id);
    }

    @PostMapping("/api/v1/admin/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new equipment item")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Asset created"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed or serial number too long (VALIDATION_FAILED, INVALID_PARAMETER)"),
        @ApiResponse(
                responseCode = "404",
                description = "Category or lab room not found (CATEGORY_NOT_FOUND, LAB_ROOM_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Serial number already exists (SERIAL_NUMBER_TAKEN)")
    })
    public AssetResponse create(@Valid @RequestBody CreateAssetRequest request) {
        return assetService.create(request);
    }

    @PutMapping("/api/v1/admin/assets/{id}")
    @Operation(summary = "Update equipment details")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Asset updated"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed or invalid parameter (VALIDATION_FAILED, INVALID_PARAMETER)"),
        @ApiResponse(
                responseCode = "404",
                description =
                        "Asset, category, or lab room not found (ASSET_NOT_FOUND, CATEGORY_NOT_FOUND, LAB_ROOM_NOT_FOUND)"),
        @ApiResponse(
                responseCode = "409",
                description = "Asset is already retired or was concurrently modified (ASSET_RETIRED, STALE_VERSION)")
    })
    public AssetResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAssetRequest request) {
        return assetService.update(id, request);
    }

    // Assets are never hard-deleted because reservations and incidents reference them.
    @PostMapping("/api/v1/admin/assets/{id}/retire")
    @Operation(summary = "Retire an equipment item instead of deleting it")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Asset retired"),
        @ApiResponse(responseCode = "400", description = "Invalid asset ID parameter (INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "404", description = "Asset not found (ASSET_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Asset is already retired (ASSET_RETIRED)")
    })
    public AssetResponse retire(@PathVariable Long id) {
        return assetService.retire(id);
    }
}

package com.campus.catalog.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.AssetSpecifications;
import com.campus.catalog.repository.CategoryRepository;
import com.campus.catalog.repository.LabRoomRepository;
import com.campus.catalog.web.AssetResponse;
import com.campus.catalog.web.CreateAssetRequest;
import com.campus.catalog.web.UpdateAssetRequest;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssetService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("name", "model", "serialNumber", "status", "createdAt", "updatedAt");

    private final AssetRepository assetRepository;
    private final CategoryRepository categoryRepository;
    private final LabRoomRepository labRoomRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AssetResponse> search(
            String search, Long categoryId, Long labRoomId, AssetStatus status, int page, int size, String sort) {
        if (page < 0) {
            throw new InvalidParameterException("Page index must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidParameterException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }

        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        boolean excludeRetired = !currentUser.isAdmin();

        Page<Asset> assets = assetRepository.findAll(
                AssetSpecifications.withFilters(search, categoryId, labRoomId, status, excludeRetired), pageable);
        return PageResponse.from(assets, AssetResponse::from);
    }

    @Transactional(readOnly = true)
    public AssetResponse getById(Long id) {
        Asset asset = assetRepository
                .findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + id + " was not found"));
        return AssetResponse.from(asset);
    }

    @Transactional
    public AssetResponse create(CreateAssetRequest request) {
        String normalizedSerial = request.serialNumber().trim().toUpperCase(Locale.ROOT);
        if (assetRepository.existsBySerialNumberIgnoreCase(normalizedSerial)) {
            throw serialTaken(normalizedSerial);
        }

        Category category = findCategoryOrThrow(request.categoryId());
        LabRoom labRoom = findLabRoomOrThrow(request.labRoomId());

        Asset asset = new Asset(
                request.name(), request.model(), normalizedSerial, request.description(), category, labRoom, now());

        try {
            Asset saved = assetRepository.saveAndFlush(asset);
            auditService.record(
                    currentUser.id(),
                    "ASSET_CREATED",
                    "ASSET",
                    saved.getId(),
                    "Created asset " + saved.getName() + " (S/N " + saved.getSerialNumber() + ")");
            return AssetResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent creates can both pass existsBySerialNumberIgnoreCase; the DB unique index stops the second
            // with 409.
            throw serialTaken(normalizedSerial);
        }
    }

    @Transactional
    public AssetResponse update(Long id, UpdateAssetRequest request) {
        Asset asset = assetRepository
                .findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + id + " was not found"));

        Category category = findCategoryOrThrow(request.categoryId());
        LabRoom labRoom = findLabRoomOrThrow(request.labRoomId());

        asset.updateDetails(request.name(), request.model(), request.description(), category, labRoom, now());
        Asset saved = assetRepository.saveAndFlush(asset);
        auditService.record(
                currentUser.id(), "ASSET_UPDATED", "ASSET", saved.getId(), "Updated asset " + saved.getName());
        return AssetResponse.from(saved);
    }

    // Uses findByIdForUpdate so a student booking in flight (CatalogApi.lockForBooking) cannot pass
    // the availability check while an admin is retiring the same asset.
    @Transactional
    public AssetResponse retire(Long id) {
        Asset asset = assetRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + id + " was not found"));

        asset.retire(now());
        Asset saved = assetRepository.saveAndFlush(asset);
        auditService.record(
                currentUser.id(),
                "ASSET_RETIRED",
                "ASSET",
                saved.getId(),
                "Retired asset " + saved.getName() + " (S/N " + saved.getSerialNumber() + ")");
        return AssetResponse.from(saved);
    }

    // Truncated to whole seconds so the response returned right after create/update matches
    // subsequent reads from PostgreSQL and the ISO-8601 format in api-contract.md.
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private Category findCategoryOrThrow(Long categoryId) {
        return categoryRepository
                .findById(categoryId)
                .orElseThrow(
                        () -> new NotFoundException("CATEGORY_NOT_FOUND", "Category " + categoryId + " was not found"));
    }

    private LabRoom findLabRoomOrThrow(Long labRoomId) {
        return labRoomRepository
                .findById(labRoomId)
                .orElseThrow(
                        () -> new NotFoundException("LAB_ROOM_NOT_FOUND", "Lab room " + labRoomId + " was not found"));
    }

    private static ConflictException serialTaken(String serial) {
        return new ConflictException(
                "SERIAL_NUMBER_TAKEN", "Serial number '" + serial + "' is already in the inventory");
    }

    // Whitelists sortable Asset fields and appends a secondary sort on id so paging stays deterministic
    // when several items share the same name, model, status, or timestamp.
    private static Sort parseSort(String sort) {
        String raw = (sort == null || sort.isBlank()) ? "name,asc" : sort.trim();
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw new InvalidParameterException("Sort parameter must have format 'field,asc' or 'field,desc'");
        }

        String field = parts[0].trim();
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new InvalidParameterException("Unsupported sort property '" + field + "'");
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2 && !parts[1].isBlank()) {
            String dirToken = parts[1].trim().toLowerCase(Locale.ROOT);
            direction = switch (dirToken) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default ->
                    throw new InvalidParameterException(
                            "Invalid sort direction '" + parts[1].trim() + "'; expected 'asc' or 'desc'");
            };
        }

        return Sort.by(direction, field).and(Sort.by(Sort.Direction.ASC, "id"));
    }
}

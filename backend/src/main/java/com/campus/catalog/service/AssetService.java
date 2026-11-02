package com.campus.catalog.service;

import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.CategoryRepository;
import com.campus.catalog.repository.LabRoomRepository;
import com.campus.catalog.web.AssetResponse;
import com.campus.catalog.web.CreateAssetRequest;
import com.campus.catalog.web.UpdateAssetRequest;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetRepository assetRepository;
    private final CategoryRepository categoryRepository;
    private final LabRoomRepository labRoomRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final Clock clock;

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
}

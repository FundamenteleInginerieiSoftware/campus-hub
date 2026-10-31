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
import java.time.Clock;
import java.time.LocalDateTime;
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
    private final Clock clock;

    @Transactional(readOnly = true)
    public AssetResponse getById(Long id) {
        Asset asset = assetRepository
                .findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + id + " was not found"));
        return AssetResponse.from(asset);
    }

    @Transactional
    public AssetResponse create(CreateAssetRequest request, Long actorId) {
        String normalizedSerial = request.serialNumber().trim().toUpperCase(Locale.ROOT);
        if (assetRepository.existsBySerialNumberIgnoreCase(normalizedSerial)) {
            throw serialTaken(normalizedSerial);
        }

        Category category = findCategoryOrThrow(request.categoryId());
        LabRoom labRoom = findLabRoomOrThrow(request.labRoomId());

        Asset asset = new Asset(
                request.name(),
                request.model(),
                normalizedSerial,
                request.description(),
                category,
                labRoom,
                LocalDateTime.now(clock));

        try {
            Asset saved = assetRepository.saveAndFlush(asset);
            auditService.record(
                    actorId,
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
    public AssetResponse update(Long id, UpdateAssetRequest request, Long actorId) {
        Asset asset = assetRepository
                .findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + id + " was not found"));

        Category category = findCategoryOrThrow(request.categoryId());
        LabRoom labRoom = findLabRoomOrThrow(request.labRoomId());

        asset.updateDetails(
                request.name(), request.model(), request.description(), category, labRoom, LocalDateTime.now(clock));
        Asset saved = assetRepository.saveAndFlush(asset);
        auditService.record(actorId, "ASSET_UPDATED", "ASSET", saved.getId(), "Updated asset " + saved.getName());
        return AssetResponse.from(saved);
    }

    // Uses findByIdForUpdate so a student booking in flight (CatalogApi.lockForBooking) cannot pass
    // the availability check while an admin is retiring the same asset.
    @Transactional
    public AssetResponse retire(Long id, Long actorId) {
        Asset asset = assetRepository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Asset " + id + " was not found"));

        asset.retire(LocalDateTime.now(clock));
        Asset saved = assetRepository.saveAndFlush(asset);
        auditService.record(
                actorId,
                "ASSET_RETIRED",
                "ASSET",
                saved.getId(),
                "Retired asset " + saved.getName() + " (S/N " + saved.getSerialNumber() + ")");
        return AssetResponse.from(saved);
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

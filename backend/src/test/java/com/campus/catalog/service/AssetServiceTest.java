package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
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
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-29T10:00:00Z");
    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, CAMPUS_ZONE);

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private LabRoomRepository labRoomRepository;

    @Mock
    private AuditService auditService;

    private AssetService assetService;
    private Category category;
    private LabRoom labRoom;

    @BeforeEach
    void setUp() {
        assetService = new AssetService(
                assetRepository,
                categoryRepository,
                labRoomRepository,
                auditService,
                Clock.fixed(FIXED_INSTANT, CAMPUS_ZONE));
        category = new Category("Oscilloscopes", "Signal lab");
        labRoom = new LabRoom("EC105", "Electronics Lab", "EC");
    }

    @Test
    void getByIdReturnsAssetDetails() {
        Asset asset = new Asset("Rigol #1", "Rigol DS1054Z", "ds1za-001", "4-ch scope", category, labRoom, FIXED_NOW);
        when(assetRepository.findWithDetailsById(7L)).thenReturn(Optional.of(asset));

        AssetResponse response = assetService.getById(7L);

        assertEquals("Rigol #1", response.name());
        assertEquals("DS1ZA-001", response.serialNumber());
        assertEquals("Oscilloscopes", response.categoryName());
        assertEquals("EC105", response.labRoomCode());
    }

    @Test
    void getByIdThrowsNotFoundWhenMissing() {
        when(assetRepository.findWithDetailsById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class, () -> assetService.getById(99L));

        assertEquals("ASSET_NOT_FOUND", ex.getCode());
    }

    @Test
    void createNormalizesSerialSavesAssetAndRecordsAudit() {
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetResponse response = assetService.create(
                new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", " ds1za-001 ", "4-ch scope", 1L, 2L), 3L);

        assertEquals("DS1ZA-001", response.serialNumber());
        assertEquals(AssetStatus.AVAILABLE, response.status());
        verify(auditService).record(3L, "ASSET_CREATED", "ASSET", null, "Created asset Rigol #1 (S/N DS1ZA-001)");
    }

    @Test
    void createRejectsDuplicateSerialNumber() {
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #2", "Rigol DS1054Z", "ds1za-001", null, 1L, 2L), 3L));

        assertEquals("SERIAL_NUMBER_TAKEN", ex.getCode());
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void createThrowsCategoryNotFoundWhenCategoryMissing() {
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false);
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", "ds1za-001", null, 99L, 2L), 3L));

        assertEquals("CATEGORY_NOT_FOUND", ex.getCode());
    }

    @Test
    void updateRejectsRetiredAsset() {
        Asset retired = new Asset("Old Uno", "Arduino Uno R3", "UNO-001", null, category, labRoom, FIXED_NOW);
        retired.retire(FIXED_NOW);
        when(assetRepository.findWithDetailsById(6L)).thenReturn(Optional.of(retired));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> assetService.update(
                        6L, new UpdateAssetRequest("Updated Uno", "Arduino Uno R3", "Desc", 1L, 2L), 3L));

        assertEquals("ASSET_RETIRED", ex.getCode());
    }

    @Test
    void retireMarksAssetRetiredAndRecordsAudit() {
        Asset asset = new Asset("Rigol #1", "Rigol DS1054Z", "DS1ZA-001", null, category, labRoom, FIXED_NOW);
        when(assetRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(asset));
        when(assetRepository.saveAndFlush(asset)).thenReturn(asset);

        AssetResponse response = assetService.retire(7L, 3L);

        assertEquals(AssetStatus.RETIRED, response.status());
        verify(auditService).record(3L, "ASSET_RETIRED", "ASSET", null, "Retired asset Rigol #1 (S/N DS1ZA-001)");
    }

    @Test
    void retireRejectsAlreadyRetiredAsset() {
        Asset asset = new Asset("Rigol #1", "Rigol DS1054Z", "DS1ZA-001", null, category, labRoom, FIXED_NOW);
        asset.retire(FIXED_NOW);
        when(assetRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(asset));

        InvalidStateTransitionException ex =
                assertThrows(InvalidStateTransitionException.class, () -> assetService.retire(7L, 3L));

        assertEquals("ASSET_RETIRED", ex.getCode());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
}

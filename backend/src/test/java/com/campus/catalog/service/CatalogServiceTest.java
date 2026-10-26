package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-24T10:00:00Z");
    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, CAMPUS_ZONE);

    @Mock
    private AssetRepository assetRepository;

    private CatalogService catalogService;
    private Category category;
    private LabRoom labRoom;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, CAMPUS_ZONE);
        catalogService = new CatalogService(assetRepository, fixedClock);
        category = new Category("Osciloscoape", "Echipamente de masura");
        labRoom = new LabRoom("B204", "Laborator Robotica", "Corp B");
    }

    @Test
    void getAssetReturnsSummaryWhenFound() {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "osc-101", "Descriere", category, labRoom, FIXED_NOW);
        when(assetRepository.findById(1L)).thenReturn(Optional.of(asset));

        AssetSummary summary = catalogService.getAsset(1L);

        assertEquals("Osciloscop", summary.name());
        assertEquals("DS1054Z", summary.model());
        assertEquals(AssetStatus.AVAILABLE, summary.status());
        assertEquals("OSC-101", asset.getSerialNumber());
    }

    @Test
    void getAssetThrowsNotFoundWhenMissing() {
        when(assetRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class, () -> catalogService.getAsset(99L));

        assertEquals("ASSET_NOT_FOUND", ex.getCode());
    }

    @Test
    void findAvailableByModelAndCategoryReturnMatchingSummaries() {
        Asset asset = new Asset("Osciloscop #2", "DS1054Z", "OSC-102", "Descriere", category, labRoom, FIXED_NOW);
        when(assetRepository.findByModelAndStatusAndIdNot("DS1054Z", AssetStatus.AVAILABLE, 1L))
                .thenReturn(List.of(asset));
        when(assetRepository.findByCategoryIdAndStatusAndIdNot(10L, AssetStatus.AVAILABLE, 1L))
                .thenReturn(List.of(asset));

        List<AssetSummary> byModel = catalogService.findAvailableByModel("DS1054Z", 1L);
        List<AssetSummary> byCategory = catalogService.findAvailableByCategory(10L, 1L);

        assertEquals(1, byModel.size());
        assertEquals("Osciloscop #2", byModel.getFirst().name());
        assertEquals(1, byCategory.size());
    }

    @Test
    void markUnderMaintenanceAndMarkAvailableUpdateStatus() {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere", category, labRoom, FIXED_NOW);
        when(assetRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(asset));

        catalogService.markUnderMaintenance(1L);
        assertEquals(AssetStatus.UNDER_MAINTENANCE, asset.getStatus());

        catalogService.markAvailable(1L);
        assertEquals(AssetStatus.AVAILABLE, asset.getStatus());
    }

    @Test
    void rejectsStatusTransitionOnRetiredAsset() {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere", category, labRoom, FIXED_NOW);
        asset.retire(FIXED_NOW);
        when(assetRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(asset));

        assertThrows(InvalidStateTransitionException.class, () -> catalogService.markAvailable(1L));
        assertThrows(InvalidStateTransitionException.class, () -> catalogService.markUnderMaintenance(1L));
    }

    @Test
    void markUnderMaintenanceAndLockForBookingThrowNotFoundWhenMissing() {
        when(assetRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> catalogService.markUnderMaintenance(99L));
        assertThrows(NotFoundException.class, () -> catalogService.lockForBooking(99L));
    }

    @Test
    void lockForBookingAcquiresRowLockWhenAssetExists() {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere", category, labRoom, FIXED_NOW);
        when(assetRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(asset));

        catalogService.lockForBooking(1L);

        verify(assetRepository).findByIdForUpdate(1L);
    }

    @Test
    void isBookableReturnsTrueOnlyWhenAvailable() {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere", category, labRoom, FIXED_NOW);
        when(assetRepository.findById(1L)).thenReturn(Optional.of(asset));

        assertTrue(catalogService.isBookable(1L));
        asset.markUnderMaintenance(FIXED_NOW);
        assertFalse(catalogService.isBookable(1L));
    }
}

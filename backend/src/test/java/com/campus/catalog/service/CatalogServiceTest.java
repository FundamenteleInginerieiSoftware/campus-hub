package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

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
        @SuppressWarnings("unchecked")
        ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
        when(clockProvider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(fixedClock);

        catalogService = new CatalogService(assetRepository, clockProvider);
        category = new Category("Osciloscoape", "Echipamente de masura");
        labRoom = new LabRoom("B204", "Laborator Robotica", "Corp B");
    }

    @Test
    void getAssetReturnsSummaryWhenFound() {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere", category, labRoom, FIXED_NOW);
        when(assetRepository.findById(1L)).thenReturn(Optional.of(asset));

        AssetSummary summary = catalogService.getAsset(1L);

        assertEquals("Osciloscop", summary.name());
        assertEquals("DS1054Z", summary.model());
        assertEquals(AssetStatus.AVAILABLE, summary.status());
    }

    @Test
    void getAssetThrowsNotFoundWhenMissing() {
        when(assetRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class, () -> catalogService.getAsset(99L));

        assertEquals("ASSET_NOT_FOUND", ex.getCode());
    }

    @Test
    void markUnderMaintenanceUpdatesStatusAndRejectsRetiredAsset() {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere", category, labRoom, FIXED_NOW);
        when(assetRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(asset));

        catalogService.markUnderMaintenance(1L);
        assertEquals(AssetStatus.UNDER_MAINTENANCE, asset.getStatus());
        assertNotEquals(AssetStatus.AVAILABLE, asset.getStatus());

        asset.retire(FIXED_NOW);
        assertThrows(InvalidStateTransitionException.class, () -> catalogService.markAvailable(1L));
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

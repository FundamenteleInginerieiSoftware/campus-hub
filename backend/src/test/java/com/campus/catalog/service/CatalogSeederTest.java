package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.CategoryRepository;
import com.campus.catalog.repository.LabRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogSeederTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-06T10:00:00Z");

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private LabRoomRepository labRoomRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private CatalogSeeder catalogSeeder;

    @Captor
    private ArgumentCaptor<List<Asset>> assetsCaptor;

    @Test
    void runSeedsFullCatalogWhenEmpty() {
        when(clock.getZone()).thenReturn(CAMPUS_ZONE);
        when(clock.instant()).thenReturn(FIXED_INSTANT);

        when(categoryRepository.count()).thenReturn(0L);
        when(labRoomRepository.count()).thenReturn(0L);
        when(assetRepository.count()).thenReturn(0L);

        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(labRoomRepository.save(any(LabRoom.class))).thenAnswer(invocation -> invocation.getArgument(0));

        catalogSeeder.run();

        verify(categoryRepository, times(4)).save(any(Category.class));
        verify(labRoomRepository, times(3)).save(any(LabRoom.class));
        verify(assetRepository).saveAll(assetsCaptor.capture());

        List<Asset> seededAssets = assetsCaptor.getValue();
        assertEquals(18, seededAssets.size());

        // Verify multiple units of the same model exist for SameModelStrategy
        long ds1054zCount = seededAssets.stream()
                .filter(a -> "DS1054Z".equals(a.getModel()))
                .count();
        assertTrue(ds1054zCount >= 3, "Expected at least 3 DS1054Z units for substitution demo");

        // Verify status distribution: available, under maintenance, and retired
        long availableCount = seededAssets.stream()
                .filter(a -> a.getStatus() == AssetStatus.AVAILABLE)
                .count();
        long maintenanceCount = seededAssets.stream()
                .filter(a -> a.getStatus() == AssetStatus.UNDER_MAINTENANCE)
                .count();
        long retiredCount = seededAssets.stream()
                .filter(a -> a.getStatus() == AssetStatus.RETIRED)
                .count();

        assertEquals(16, availableCount);
        assertEquals(1, maintenanceCount);
        assertEquals(1, retiredCount);

        // First asset must be AVAILABLE so ReservationSeeder can book it
        assertEquals(AssetStatus.AVAILABLE, seededAssets.get(0).getStatus());
    }

    @Test
    void runSkipsWhenCategoriesAlreadyExist() {
        when(categoryRepository.count()).thenReturn(4L);

        catalogSeeder.run();

        verify(categoryRepository, never()).save(any());
        verify(labRoomRepository, never()).save(any());
        verify(assetRepository, never()).saveAll(anyList());
    }

    @Test
    void runSkipsWhenLabRoomsAlreadyExist() {
        when(categoryRepository.count()).thenReturn(0L);
        when(labRoomRepository.count()).thenReturn(3L);

        catalogSeeder.run();

        verify(categoryRepository, never()).save(any());
        verify(labRoomRepository, never()).save(any());
        verify(assetRepository, never()).saveAll(anyList());
    }

    @Test
    void runSkipsWhenAssetsAlreadyExist() {
        when(categoryRepository.count()).thenReturn(0L);
        when(labRoomRepository.count()).thenReturn(0L);
        when(assetRepository.count()).thenReturn(10L);

        catalogSeeder.run();

        verify(categoryRepository, never()).save(any());
        verify(labRoomRepository, never()).save(any());
        verify(assetRepository, never()).saveAll(anyList());
    }
}

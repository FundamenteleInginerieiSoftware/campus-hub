package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SameCategoryStrategyTest {

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    private SameCategoryStrategy strategy;

    private final LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
    private final LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
    private final ReservationSummary reservation =
            new ReservationSummary(1L, 42L, 10L, start, end, ReservationStatus.CONFIRMED);
    private final AssetSummary damaged =
            new AssetSummary(10L, "Damaged Scope", "DS1054Z", 5L, 100L, AssetStatus.AVAILABLE);

    @BeforeEach
    void setUp() {
        strategy = new SameCategoryStrategy(catalogApi, reservationApi);
    }

    @Test
    @DisplayName("name() returns SameCategoryStrategy")
    void nameReturnsCorrectIdentifier() {
        assertThat(strategy.name()).isEqualTo("SameCategoryStrategy");
    }

    @Test
    @DisplayName("Prioritizes candidate in the same lab room within same category")
    void prioritizesSameLabRoomCandidate() {
        AssetSummary differentRoomCandidate =
                new AssetSummary(20L, "Other Scope Room 200", "DS2000", 5L, 200L, AssetStatus.AVAILABLE);
        AssetSummary sameRoomCandidate =
                new AssetSummary(30L, "Other Scope Room 100", "DS2000", 5L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByCategory(5L, 10L))
                .thenReturn(List.of(differentRoomCandidate, sameRoomCandidate));
        when(reservationApi.isAssetFree(eq(30L), eq(start), eq(end), eq(1L))).thenReturn(true);

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(30L);
    }

    @Test
    @DisplayName("Picks free candidate in different room when candidate in same room is busy")
    void picksDifferentRoomCandidateWhenSameRoomIsBusy() {
        AssetSummary differentRoomCandidate =
                new AssetSummary(20L, "Other Scope Room 200", "DS2000", 5L, 200L, AssetStatus.AVAILABLE);
        AssetSummary sameRoomCandidate =
                new AssetSummary(30L, "Other Scope Room 100", "DS2000", 5L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByCategory(5L, 10L))
                .thenReturn(List.of(sameRoomCandidate, differentRoomCandidate));
        when(reservationApi.isAssetFree(eq(30L), eq(start), eq(end), eq(1L))).thenReturn(false);
        when(reservationApi.isAssetFree(eq(20L), eq(start), eq(end), eq(1L))).thenReturn(true);

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Returns empty when all candidates in category are busy")
    void returnsEmptyWhenAllCandidatesBusy() {
        AssetSummary candidate =
                new AssetSummary(20L, "Other Scope Room 200", "DS2000", 5L, 200L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByCategory(5L, 10L)).thenReturn(List.of(candidate));
        when(reservationApi.isAssetFree(eq(20L), eq(start), eq(end), eq(1L))).thenReturn(false);

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty when catalog has no available assets in category")
    void returnsEmptyWhenCatalogReturnsNoCandidates() {
        when(catalogApi.findAvailableByCategory(5L, 10L)).thenReturn(List.of());

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Strictly excludes candidates with RETIRED status even if free")
    void strictlyExcludesRetiredCandidates() {
        AssetSummary retiredCandidate = new AssetSummary(20L, "Retired Scope", "DS2000", 5L, 100L, AssetStatus.RETIRED);

        when(catalogApi.findAvailableByCategory(5L, 10L)).thenReturn(List.of(retiredCandidate));

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Strictly excludes candidates with UNDER_MAINTENANCE status")
    void strictlyExcludesUnderMaintenanceCandidates() {
        AssetSummary maintenanceCandidate =
                new AssetSummary(20L, "Broken Scope", "DS2000", 5L, 100L, AssetStatus.UNDER_MAINTENANCE);

        when(catalogApi.findAvailableByCategory(5L, 10L)).thenReturn(List.of(maintenanceCandidate));

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Breaks ties deterministically by lowest asset ID when both in same room and free")
    void breaksTieByLowestAssetId() {
        AssetSummary higherId = new AssetSummary(50L, "Scope 50", "DS2000", 5L, 100L, AssetStatus.AVAILABLE);
        AssetSummary lowerId = new AssetSummary(20L, "Scope 20", "DS2000", 5L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByCategory(5L, 10L)).thenReturn(List.of(higherId, lowerId));
        when(reservationApi.isAssetFree(eq(20L), eq(start), eq(end), eq(1L))).thenReturn(true);

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(20L);
    }
}

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
class SameModelStrategyTest {

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    private SameModelStrategy strategy;

    private final LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
    private final LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
    private final ReservationSummary reservation =
            new ReservationSummary(1L, 42L, 10L, start, end, ReservationStatus.CONFIRMED);
    private final AssetSummary damaged =
            new AssetSummary(10L, "Damaged Scope", "DS1054Z", 1L, 100L, AssetStatus.AVAILABLE);

    @BeforeEach
    void setUp() {
        strategy = new SameModelStrategy(catalogApi, reservationApi);
    }

    @Test
    @DisplayName("name() returns SameModelStrategy")
    void nameReturnsCorrectIdentifier() {
        assertThat(strategy.name()).isEqualTo("SameModelStrategy");
    }

    @Test
    @DisplayName("Prioritizes candidate in the same lab room when both candidates are free")
    void prioritizesSameLabRoomCandidate() {
        AssetSummary differentRoomCandidate =
                new AssetSummary(20L, "Scope Room 200", "DS1054Z", 1L, 200L, AssetStatus.AVAILABLE);
        AssetSummary sameRoomCandidate =
                new AssetSummary(30L, "Scope Room 100", "DS1054Z", 1L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByModel("DS1054Z", 10L))
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
                new AssetSummary(20L, "Scope Room 200", "DS1054Z", 1L, 200L, AssetStatus.AVAILABLE);
        AssetSummary sameRoomCandidate =
                new AssetSummary(30L, "Scope Room 100", "DS1054Z", 1L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByModel("DS1054Z", 10L))
                .thenReturn(List.of(sameRoomCandidate, differentRoomCandidate));
        when(reservationApi.isAssetFree(eq(30L), eq(start), eq(end), eq(1L))).thenReturn(false);
        when(reservationApi.isAssetFree(eq(20L), eq(start), eq(end), eq(1L))).thenReturn(true);

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Returns empty when all candidates of same model are busy")
    void returnsEmptyWhenAllCandidatesBusy() {
        AssetSummary candidate = new AssetSummary(20L, "Scope Room 200", "DS1054Z", 1L, 200L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByModel("DS1054Z", 10L)).thenReturn(List.of(candidate));
        when(reservationApi.isAssetFree(eq(20L), eq(start), eq(end), eq(1L))).thenReturn(false);

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty when catalog has no available assets of same model")
    void returnsEmptyWhenCatalogReturnsNoCandidates() {
        when(catalogApi.findAvailableByModel("DS1054Z", 10L)).thenReturn(List.of());

        Optional<AssetSummary> result = strategy.findSubstitute(reservation, damaged);

        assertThat(result).isEmpty();
    }
}

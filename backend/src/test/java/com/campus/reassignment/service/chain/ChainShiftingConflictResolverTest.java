package com.campus.reassignment.service.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import java.time.Clock;
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
class ChainShiftingConflictResolverTest {

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private Clock clock;

    private ChainShiftingConflictResolver resolver;

    private final LocalDateTime fixedNow = LocalDateTime.of(2026, 11, 10, 8, 0);
    private final LocalDateTime t10 = LocalDateTime.of(2026, 11, 15, 10, 0);
    private final LocalDateTime t12 = LocalDateTime.of(2026, 11, 15, 12, 0);

    @BeforeEach
    void setUp() {
        resolver = new ChainShiftingConflictResolver(catalogApi, reservationApi, clock);
    }

    @Test
    @DisplayName("Target asset already free: returns resolved true with 0 steps")
    void planChainShift_targetAlreadyFree() {
        when(reservationApi.isAssetFree(1L, t10, t12, null)).thenReturn(true);

        ChainShiftPlan plan = resolver.planChainShift(1L, t10, t12);

        assertThat(plan.resolved())
                .as("already free equipment must resolve immediately; see ChainShiftingConflictResolver.planChainShift")
                .isTrue();
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    @DisplayName("Single-step shift: Res A on Asset 1 shifts to idle substitute Asset 2")
    void planChainShift_singleStepAugmentingPath() {
        when(reservationApi.isAssetFree(1L, t10, t12, null)).thenReturn(false);
        when(clock.instant())
                .thenReturn(
                        fixedNow.atZone(java.time.ZoneId.of("Europe/Bucharest")).toInstant());
        when(clock.getZone()).thenReturn(java.time.ZoneId.of("Europe/Bucharest"));

        AssetSummary asset1 = new AssetSummary(1L, "Scope X", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary asset2 = new AssetSummary(2L, "Scope Y", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(1L)).thenReturn(asset1);

        ReservationSummary resA = new ReservationSummary(101L, 42L, 1L, t10, t12, ReservationStatus.CONFIRMED);
        when(reservationApi.findUpcomingActiveByAsset(eq(1L), any())).thenReturn(List.of(resA));

        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of(asset2));
        when(catalogApi.findAvailableByCategory(1L, 1L)).thenReturn(List.of(asset2));

        // Asset 2 is completely idle during [10:00, 12:00]
        when(reservationApi.isAssetFree(2L, t10, t12, null)).thenReturn(true);

        ChainShiftPlan plan = resolver.planChainShift(1L, t10, t12);

        assertThat(plan.resolved()).isTrue();
        assertThat(plan.steps()).hasSize(1);
        ChainShiftStep step = plan.steps().get(0);
        assertThat(step.reservationId()).isEqualTo(101L);
        assertThat(step.fromAssetId()).isEqualTo(1L);
        assertThat(step.toAssetId()).isEqualTo(2L);
        assertThat(step.strategyName()).isEqualTo("SameModel");
    }

    @Test
    @DisplayName("Two-step augmenting path chain: Res A on X -> Y, Res B on Y -> Z (idle)")
    void planChainShift_twoStepAugmentingPathChain() {
        when(reservationApi.isAssetFree(1L, t10, t12, null)).thenReturn(false);
        when(clock.instant())
                .thenReturn(
                        fixedNow.atZone(java.time.ZoneId.of("Europe/Bucharest")).toInstant());
        when(clock.getZone()).thenReturn(java.time.ZoneId.of("Europe/Bucharest"));

        AssetSummary assetX = new AssetSummary(1L, "Scope X", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary assetY = new AssetSummary(2L, "Scope Y", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary assetZ = new AssetSummary(3L, "Scope Z", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(1L)).thenReturn(assetX);

        // Blocker on X: Res A
        ReservationSummary resA = new ReservationSummary(101L, 42L, 1L, t10, t12, ReservationStatus.CONFIRMED);
        when(reservationApi.findUpcomingActiveByAsset(eq(1L), any())).thenReturn(List.of(resA));

        // Candidates for X: only Y
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of(assetY));
        when(catalogApi.findAvailableByCategory(1L, 1L)).thenReturn(List.of(assetY));

        // Asset Y is NOT free! It is blocked by Res B
        when(reservationApi.isAssetFree(2L, t10, t12, null)).thenReturn(false);
        ReservationSummary resB = new ReservationSummary(102L, 43L, 2L, t10, t12, ReservationStatus.CONFIRMED);
        when(reservationApi.findUpcomingActiveByAsset(eq(2L), any())).thenReturn(List.of(resB));

        // Candidates for Y: Z
        when(catalogApi.findAvailableByModel("DS1054Z", 2L)).thenReturn(List.of(assetZ));
        when(catalogApi.findAvailableByCategory(1L, 2L)).thenReturn(List.of(assetZ));

        // Asset Z is IDLE!
        when(reservationApi.isAssetFree(3L, t10, t12, null)).thenReturn(true);

        ChainShiftPlan plan = resolver.planChainShift(1L, t10, t12);

        assertThat(plan.resolved()).isTrue();
        assertThat(plan.steps()).hasSize(2);

        // Execution order: Res B must move Y -> Z FIRST, freeing Y; then Res A moves X -> Y
        ChainShiftStep firstMove = plan.steps().get(0);
        assertThat(firstMove.reservationId()).isEqualTo(102L);
        assertThat(firstMove.fromAssetId()).isEqualTo(2L);
        assertThat(firstMove.toAssetId()).isEqualTo(3L);

        ChainShiftStep secondMove = plan.steps().get(1);
        assertThat(secondMove.reservationId()).isEqualTo(101L);
        assertThat(secondMove.fromAssetId()).isEqualTo(1L);
        assertThat(secondMove.toAssetId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("No augmenting path exists: all candidate assets busy or exhausted")
    void planChainShift_noAugmentingPathFailsGracefully() {
        when(reservationApi.isAssetFree(1L, t10, t12, null)).thenReturn(false);
        when(clock.instant())
                .thenReturn(
                        fixedNow.atZone(java.time.ZoneId.of("Europe/Bucharest")).toInstant());
        when(clock.getZone()).thenReturn(java.time.ZoneId.of("Europe/Bucharest"));

        AssetSummary assetX = new AssetSummary(1L, "Scope X", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(1L)).thenReturn(assetX);

        ReservationSummary resA = new ReservationSummary(101L, 42L, 1L, t10, t12, ReservationStatus.CONFIRMED);
        when(reservationApi.findUpcomingActiveByAsset(eq(1L), any())).thenReturn(List.of(resA));

        // No substitute equipment exists in the lab
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of());
        when(catalogApi.findAvailableByCategory(1L, 1L)).thenReturn(List.of());

        ChainShiftPlan plan = resolver.planChainShift(1L, t10, t12);

        assertThat(plan.resolved()).isFalse();
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.explanation()).contains("Unable to find an augmenting shift chain");
    }

    @Test
    @DisplayName(
            "Target not free but no shiftable CONFIRMED reservations: returns resolved false instead of phantom success")
    void planChainShift_targetOccupiedByInUseReservationFailsGracefully() {
        when(reservationApi.isAssetFree(1L, t10, t12, null)).thenReturn(false);
        when(clock.instant())
                .thenReturn(
                        fixedNow.atZone(java.time.ZoneId.of("Europe/Bucharest")).toInstant());
        when(clock.getZone()).thenReturn(java.time.ZoneId.of("Europe/Bucharest"));

        AssetSummary assetX = new AssetSummary(1L, "Scope X", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(1L)).thenReturn(assetX);

        // findUpcomingActiveByAsset returns only CONFIRMED reservations; if asset is occupied by OVERDUE or
        // CHECKED_OUT, list is empty
        when(reservationApi.findUpcomingActiveByAsset(eq(1L), any())).thenReturn(List.of());

        ChainShiftPlan plan = resolver.planChainShift(1L, t10, t12);

        assertThat(plan.resolved())
                .as(
                        "occupied asset without shiftable confirmed reservations cannot be resolved; see ChainShiftingConflictResolver.planChainShift")
                .isFalse();
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.explanation())
                .contains("is not free during the requested window, but has no shiftable confirmed reservations");
    }

    @Test
    @DisplayName("Target has a CONFIRMED blocker and an overlapping checkout: resolved false, nothing planned")
    void planChainShift_targetAlsoCheckedOutIsNotResolved() {
        LocalDateTime t0930 = t10.minusMinutes(30);
        LocalDateTime t1400 = t12.plusHours(2);
        when(reservationApi.isAssetFree(1L, t0930, t1400, null)).thenReturn(false);
        when(clock.instant())
                .thenReturn(
                        fixedNow.atZone(java.time.ZoneId.of("Europe/Bucharest")).toInstant());
        when(clock.getZone()).thenReturn(java.time.ZoneId.of("Europe/Bucharest"));

        AssetSummary assetX = new AssetSummary(1L, "Scope X", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(1L)).thenReturn(assetX);

        // 12:00-14:00 CONFIRMED (shiftable) and 08:00-10:00 CHECKED_OUT (not shiftable, overlaps 09:30)
        ReservationSummary confirmed =
                new ReservationSummary(601L, 42L, 1L, t12, t1400, ReservationStatus.CONFIRMED);
        when(reservationApi.findUpcomingActiveByAsset(eq(1L), any())).thenReturn(List.of(confirmed));
        ReservationSummary checkout = new ReservationSummary(
                600L, 41L, 1L, t10.minusHours(2), t10, ReservationStatus.CHECKED_OUT);
        when(reservationApi.findCurrentCheckout(1L)).thenReturn(Optional.of(checkout));

        ChainShiftPlan plan = resolver.planChainShift(1L, t0930, t1400);

        assertThat(plan.resolved())
                .as("moving the CONFIRMED booking cannot free X while it is checked out; see isHeldByCheckout")
                .isFalse();
        assertThat(plan.steps()).as("no student may be moved for nothing").isEmpty();
        assertThat(plan.explanation()).contains("in use");
    }

    @Test
    @DisplayName("Candidate Y has one CONFIRMED blocker and is OVERDUE: not used as a chain link")
    void planChainShift_candidateHeldByCheckoutIsSkipped() {
        when(reservationApi.isAssetFree(1L, t10, t12, null)).thenReturn(false);
        when(clock.instant())
                .thenReturn(
                        fixedNow.atZone(java.time.ZoneId.of("Europe/Bucharest")).toInstant());
        when(clock.getZone()).thenReturn(java.time.ZoneId.of("Europe/Bucharest"));

        AssetSummary assetX = new AssetSummary(1L, "Scope X", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary assetY = new AssetSummary(2L, "Scope Y", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(1L)).thenReturn(assetX);

        ReservationSummary resA = new ReservationSummary(101L, 42L, 1L, t10, t12, ReservationStatus.CONFIRMED);
        when(reservationApi.findUpcomingActiveByAsset(eq(1L), any())).thenReturn(List.of(resA));
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of(assetY));
        when(catalogApi.findAvailableByCategory(1L, 1L)).thenReturn(List.of(assetY));

        when(reservationApi.isAssetFree(2L, t10, t12, null)).thenReturn(false);
        ReservationSummary resB = new ReservationSummary(102L, 43L, 2L, t10, t12, ReservationStatus.CONFIRMED);
        when(reservationApi.findUpcomingActiveByAsset(eq(2L), any())).thenReturn(List.of(resB));
        ReservationSummary overdue = new ReservationSummary(
                99L, 44L, 2L, t10.minusDays(1), t12.minusDays(1), ReservationStatus.OVERDUE);
        when(reservationApi.findCurrentCheckout(2L)).thenReturn(Optional.of(overdue));

        ChainShiftPlan plan = resolver.planChainShift(1L, t10, t12);

        assertThat(plan.resolved())
                .as("an OVERDUE unit stays busy even if its CONFIRMED booking is shifted")
                .isFalse();
        assertThat(plan.steps()).isEmpty();
    }
}

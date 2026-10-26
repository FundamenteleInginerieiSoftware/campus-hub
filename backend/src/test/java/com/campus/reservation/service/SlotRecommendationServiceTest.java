package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.SlotRecommendationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SlotRecommendationServiceTest {

    private static final Long TARGET_ASSET_ID = 10L;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationService reservationService;

    @Mock
    private CatalogApi catalogApi;

    private Clock clock;
    private SlotRecommendationService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        service = new SlotRecommendationService(reservationRepository, reservationService, catalogApi, clock);
    }

    @Test
    @DisplayName("Sweep-line subtracts occupied intervals and ranks substitute assets by model, room, and daily load")
    void recommendsClosestFreeSlotsAndRanksSubstitutes() {
        // Desired: Tuesday 2026-11-03 10:00 - 12:00 (occupied on TARGET_ASSET_ID from 09:30 to 12:30)
        LocalDateTime desiredStart = LocalDateTime.of(2026, 11, 3, 10, 0);
        LocalDateTime desiredEnd = LocalDateTime.of(2026, 11, 3, 12, 0);

        AssetSummary target = new AssetSummary(TARGET_ASSET_ID, "Scope A", "Tek-TBS", 1L, 101L, AssetStatus.AVAILABLE);
        AssetSummary subSameModelSameRoom =
                new AssetSummary(20L, "Scope B", "Tek-TBS", 1L, 101L, AssetStatus.AVAILABLE);
        AssetSummary subSameCategoryOtherRoom =
                new AssetSummary(30L, "Rigol C", "Rigol-DS", 1L, 202L, AssetStatus.AVAILABLE);

        Reservation existingOnTarget = new Reservation(
                1L,
                TARGET_ASSET_ID,
                LocalDateTime.of(2026, 11, 3, 9, 30),
                LocalDateTime.of(2026, 11, 3, 12, 30),
                LocalDateTime.of(2026, 11, 2, 10, 0));

        when(catalogApi.getAsset(TARGET_ASSET_ID)).thenReturn(target);
        when(catalogApi.isBookable(TARGET_ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        TARGET_ASSET_ID, desiredStart, desiredEnd, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(true);
        when(reservationRepository.findActiveByAssetInWindow(
                        eq(TARGET_ASSET_ID), any(), any(), eq(ReservationStatus.ACTIVE_STATUSES)))
                .thenReturn(List.of(existingOnTarget));

        when(catalogApi.findAvailableByModel("Tek-TBS", TARGET_ASSET_ID)).thenReturn(List.of(subSameModelSameRoom));
        when(catalogApi.findAvailableByCategory(1L, TARGET_ASSET_ID)).thenReturn(List.of(subSameCategoryOtherRoom));
        when(reservationRepository.existsOverlap(20L, desiredStart, desiredEnd, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.existsOverlap(30L, desiredStart, desiredEnd, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.findActiveByAssetInWindow(
                        eq(20L), any(), any(), eq(ReservationStatus.ACTIVE_STATUSES)))
                .thenReturn(List.of());
        when(reservationRepository.findActiveByAssetInWindow(
                        eq(30L), any(), any(), eq(ReservationStatus.ACTIVE_STATUSES)))
                .thenReturn(List.of());

        SlotRecommendationResponse response = service.recommend(TARGET_ASSET_ID, desiredStart, desiredEnd);

        assertThat(response.exactSlotAvailable()).isFalse();
        assertThat(response.alternativeSlotsOnSameAsset()).isNotEmpty();
        // Closest 2-hour slot after [09:30, 12:30] is [12:30, 14:30] (shifted by 150 minutes)
        assertThat(response.alternativeSlotsOnSameAsset().get(0).startTime())
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 12, 30));
        assertThat(response.alternativeSlotsOnSameAsset().get(0).endTime())
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 14, 30));

        // Substitute 20L (same model +60, same room +30, idle +10 = 100) ranks above 30L (same cat +30, diff room +0,
        // idle +10 = 40)
        assertThat(response.availableSubstitutesForExactSlot()).hasSize(2);
        assertThat(response.availableSubstitutesForExactSlot().get(0).assetId()).isEqualTo(20L);
        assertThat(response.availableSubstitutesForExactSlot().get(0).compatibilityScore())
                .isEqualTo(100);
        assertThat(response.availableSubstitutesForExactSlot().get(1).assetId()).isEqualTo(30L);
        assertThat(response.availableSubstitutesForExactSlot().get(1).compatibilityScore())
                .isEqualTo(40);
    }

    @Test
    @DisplayName("Rejects invalid duration or weekend slot via shared ReservationService slot rules")
    void rejectsUnalignedDuration() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 10, 45);
        doThrow(new BusinessRuleException("INVALID_TIME_SLOT", "Must align to :00 or :30"))
                .when(reservationService)
                .validateSlotRules(eq(start), eq(end), any());

        assertThatThrownBy(() -> service.recommend(TARGET_ASSET_ID, start, end))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TIME_SLOT");
    }
}

package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.web.MaintenanceImpactResponse;
import com.campus.reassignment.web.SimulateMaintenanceRequest;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaintenanceImpactSimulatorTest {

    private static final Long TARGET_ASSET_ID = 10L;
    private static final Long SUB_SAME_MODEL_ID = 20L;
    private static final Long SUB_SAME_CATEGORY_ID = 30L;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private SubstituteStrategy sameModelStrategy;

    @Mock
    private SubstituteStrategy sameCategoryStrategy;

    private Clock clock;
    private MaintenanceImpactSimulator simulator;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-02T07:00:00Z"), ZoneId.of("Europe/Bucharest"));
        simulator = new MaintenanceImpactSimulator(
                catalogApi, reservationApi, List.of(sameModelStrategy, sameCategoryStrategy), clock);
    }

    @Test
    @DisplayName("Delegates to injected SubstituteStrategy chain to predict outcomes")
    void delegatesToInjectedStrategies() {
        LocalDateTime maintStart = LocalDateTime.of(2026, 11, 2, 10, 0);
        LocalDateTime maintEnd = LocalDateTime.of(2026, 11, 2, 12, 0);

        AssetSummary target =
                new AssetSummary(TARGET_ASSET_ID, "Oscilloscope A", "Tektronix-TBS", 1L, 101L, AssetStatus.AVAILABLE);
        AssetSummary subModel =
                new AssetSummary(SUB_SAME_MODEL_ID, "Oscilloscope B", "Tektronix-TBS", 1L, 101L, AssetStatus.AVAILABLE);
        AssetSummary subCategory =
                new AssetSummary(SUB_SAME_CATEGORY_ID, "Rigol Scope", "Rigol-DS", 1L, 102L, AssetStatus.AVAILABLE);

        ReservationSummary b1 = new ReservationSummary(
                1001L,
                1L,
                TARGET_ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 11, 0),
                ReservationStatus.CONFIRMED);
        ReservationSummary b2 = new ReservationSummary(
                1002L,
                2L,
                TARGET_ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 30),
                LocalDateTime.of(2026, 11, 2, 11, 30),
                ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(TARGET_ASSET_ID)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(eq(TARGET_ASSET_ID), any()))
                .thenReturn(List.of(b1, b2));

        when(sameModelStrategy.findSubstitute(b1, target)).thenReturn(Optional.of(subModel));
        when(sameModelStrategy.name()).thenReturn("SameModelStrategy");

        when(sameModelStrategy.findSubstitute(b2, target)).thenReturn(Optional.empty());
        when(sameCategoryStrategy.findSubstitute(b2, target)).thenReturn(Optional.of(subCategory));
        when(sameCategoryStrategy.name()).thenReturn("SameCategoryStrategy");

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(TARGET_ASSET_ID, maintStart, maintEnd));

        assertThat(response.totalAffectedReservations()).isEqualTo(2);
        assertThat(response.predictedReassignedCount()).isEqualTo(2);
        assertThat(response.unresolvableForceCancelCount()).isZero();
        assertThat(response.simulatedItems().get(0).suggestedSubstituteAssetId())
                .isEqualTo(SUB_SAME_MODEL_ID);
        assertThat(response.simulatedItems().get(0).strategyUsed()).isEqualTo("SameModelStrategy");
        assertThat(response.simulatedItems().get(1).suggestedSubstituteAssetId())
                .isEqualTo(SUB_SAME_CATEGORY_ID);
        assertThat(response.simulatedItems().get(1).strategyUsed()).isEqualTo("SameCategoryStrategy");
    }

    @Test
    @DisplayName("Predicts FORCE_CANCELLED when all injected strategies return empty")
    void predictsForceCancelledWhenNoSubstitutesAvailable() {
        LocalDateTime maintStart = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime maintEnd = LocalDateTime.of(2026, 11, 2, 16, 0);

        AssetSummary target =
                new AssetSummary(TARGET_ASSET_ID, "Oscilloscope A", "Tektronix-TBS", 1L, 101L, AssetStatus.AVAILABLE);
        ReservationSummary b1 =
                new ReservationSummary(1001L, 1L, TARGET_ASSET_ID, maintStart, maintEnd, ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(TARGET_ASSET_ID)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(eq(TARGET_ASSET_ID), any()))
                .thenReturn(List.of(b1));
        when(sameModelStrategy.findSubstitute(b1, target)).thenReturn(Optional.empty());
        when(sameCategoryStrategy.findSubstitute(b1, target)).thenReturn(Optional.empty());

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(TARGET_ASSET_ID, maintStart, maintEnd));

        assertThat(response.unresolvableForceCancelCount()).isEqualTo(1);
        assertThat(response.simulatedItems().get(0).predictedOutcome()).isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
    }

    @Test
    @DisplayName("Rejects simulation request when maintenanceEnd is not after maintenanceStart")
    void rejectsInvalidTimeRange() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 2, 14, 0);
        SimulateMaintenanceRequest req = new SimulateMaintenanceRequest(TARGET_ASSET_ID, time, time);

        assertThatThrownBy(() -> simulator.simulate(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TIME_RANGE");
    }
}

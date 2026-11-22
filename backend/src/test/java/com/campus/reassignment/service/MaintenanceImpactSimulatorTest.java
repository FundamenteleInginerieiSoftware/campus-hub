package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaintenanceImpactSimulatorTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    private MaintenanceImpactSimulator simulator;

    @BeforeEach
    void setUp() {
        SameModelStrategy sameModel = new SameModelStrategy(catalogApi, reservationApi);
        SameCategoryStrategy sameCategory = new SameCategoryStrategy(catalogApi, reservationApi);
        simulator = new MaintenanceImpactSimulator(catalogApi, reservationApi, List.of(sameModel, sameCategory), clock);
    }

    @Test
    @DisplayName("simulate tracks virtual substitute allocations in memory and falls back to category or force-cancel")
    void simulate_tracksVirtualAllocationsAndComputesDisruptionScore() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowStart = now.plusHours(2);
        LocalDateTime windowEnd = now.plusHours(6);

        AssetSummary target = new AssetSummary(1L, "Scope 1", "DS1054Z", 10L, 100L, AssetStatus.AVAILABLE);
        AssetSummary sameModelSub = new AssetSummary(2L, "Scope 2", "DS1054Z", 10L, 100L, AssetStatus.AVAILABLE);
        AssetSummary sameCatSub = new AssetSummary(3L, "Scope 3", "TDS2000", 10L, 100L, AssetStatus.AVAILABLE);

        ReservationSummary b1 = new ReservationSummary(
                11L, 501L, 1L, windowStart, windowStart.plusHours(2), ReservationStatus.CONFIRMED);
        // Overlapping b2 cannot reuse sameModelSub because virtualAllocations already assigned it to b1.
        ReservationSummary b2 = new ReservationSummary(
                12L, 502L, 1L, windowStart.plusHours(1), windowStart.plusHours(3), ReservationStatus.CONFIRMED);
        // Non-overlapping b3 outside the maintenance window must not be counted.
        ReservationSummary outside = new ReservationSummary(
                13L, 503L, 1L, windowEnd.plusHours(1), windowEnd.plusHours(2), ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(1L)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(1L, now)).thenReturn(List.of(b1, b2, outside));
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of(sameModelSub));
        when(catalogApi.findAvailableByCategory(10L, 1L)).thenReturn(List.of(sameModelSub, sameCatSub));
        when(reservationApi.isAssetFree(2L, b1.start(), b1.end(), 11L)).thenReturn(true);
        when(reservationApi.isAssetFree(2L, b2.start(), b2.end(), 12L)).thenReturn(true);
        // SameCategory candidate 3 is busy for b2, forcing FORCE_CANCELLED.
        when(reservationApi.isAssetFree(3L, b2.start(), b2.end(), 12L)).thenReturn(false);

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(1L, windowStart, windowEnd));

        assertThat(response.totalAffected())
                .as(
                        "only bookings overlapping the maintenance window must be simulated; see MaintenanceImpactSimulator.simulate")
                .isEqualTo(2);
        assertThat(response.reassignedCount()).isEqualTo(1);
        assertThat(response.forceCancelledCount()).isEqualTo(1);
        assertThat(response.disruptionScore())
                .as(
                        "disruptionScore must equal 1*reassigned + 3*forceCancelled; see MaintenanceImpactSimulator.simulate")
                .isEqualTo(4);
        assertThat(response.bookings().get(0).outcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(response.bookings().get(0).substituteAssetId()).isEqualTo(2L);
        assertThat(response.bookings().get(1).outcome())
                .as(
                        "overlapping booking must not reuse virtually allocated substitute #2; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
        verifyNoMoreInteractions(reservationApi);
    }

    @Test
    @DisplayName("simulate rejects maintenance windows where end is not after start")
    void simulate_rejectsInvalidWindow() {
        LocalDateTime now = LocalDateTime.now(clock);
        assertThatThrownBy(() ->
                        simulator.simulate(new SimulateMaintenanceRequest(1L, now.plusHours(2), now.plusHours(2))))
                .as(
                        "non-positive maintenance window must throw INVALID_TIME_RANGE; see MaintenanceImpactSimulator.simulate")
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("INVALID_TIME_RANGE");
    }
}

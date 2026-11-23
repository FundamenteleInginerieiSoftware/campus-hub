package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private LocalDateTime now;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private AssetSummary target;

    @BeforeEach
    void setUp() {
        SameModelStrategy sameModel = new SameModelStrategy(catalogApi, reservationApi);
        SameCategoryStrategy sameCategory = new SameCategoryStrategy(catalogApi, reservationApi);
        simulator = new MaintenanceImpactSimulator(catalogApi, reservationApi, List.of(sameModel, sameCategory), clock);
        now = LocalDateTime.now(clock);
        windowStart = now.plusHours(2);
        windowEnd = now.plusHours(6);
        target = new AssetSummary(1L, "Scope 1", "DS1054Z", 10L, 100L, AssetStatus.AVAILABLE);
    }

    @Test
    @DisplayName("simulate includes only upcoming bookings overlapping the maintenance window and makes no DB writes")
    void simulate_filtersOnlyBookingsOverlappingWindow() {
        ReservationSummary inside = new ReservationSummary(
                11L, 501L, 1L, windowStart, windowStart.plusHours(2), ReservationStatus.CONFIRMED);
        ReservationSummary touchingBefore = new ReservationSummary(
                12L, 502L, 1L, windowStart.minusHours(1), windowStart, ReservationStatus.CONFIRMED);
        ReservationSummary outsideAfter =
                new ReservationSummary(13L, 503L, 1L, windowEnd, windowEnd.plusHours(2), ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(1L)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(1L, now))
                .thenReturn(List.of(inside, touchingBefore, outsideAfter));
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of());
        when(catalogApi.findAvailableByCategory(10L, 1L)).thenReturn(List.of());

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(1L, windowStart, windowEnd));

        assertThat(response.totalAffected())
                .as(
                        "only bookings strictly overlapping [windowStart, windowEnd] must be included; see MaintenanceImpactSimulator.simulate")
                .isEqualTo(1);
        assertThat(response.bookings())
                .as("non-overlapping edge bookings must be excluded; see MaintenanceImpactSimulator.simulate")
                .extracting(b -> b.reservationId())
                .containsExactly(11L);
        verify(reservationApi, never()).moveToAsset(anyLong(), anyLong(), anyLong(), any());
        verify(reservationApi, never()).forceCancel(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("simulate tracks virtual allocations in memory so overlapping bookings never share one substitute")
    void simulate_preventsVirtualDoubleAllocationOfSameSubstitute() {
        AssetSummary sameModelSub = new AssetSummary(2L, "Scope 2", "DS1054Z", 10L, 100L, AssetStatus.AVAILABLE);
        ReservationSummary first = new ReservationSummary(
                11L, 501L, 1L, windowStart, windowStart.plusHours(2), ReservationStatus.CONFIRMED);
        ReservationSummary overlapping = new ReservationSummary(
                12L, 502L, 1L, windowStart.plusHours(1), windowStart.plusHours(3), ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(1L)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(1L, now)).thenReturn(List.of(first, overlapping));
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of(sameModelSub));
        when(catalogApi.findAvailableByCategory(10L, 1L)).thenReturn(List.of(sameModelSub));
        when(reservationApi.isAssetFree(2L, first.start(), first.end(), 11L)).thenReturn(true);
        when(reservationApi.isAssetFree(2L, overlapping.start(), overlapping.end(), 12L))
                .thenReturn(true);

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(1L, windowStart, windowEnd));

        assertThat(response.bookings().get(0).outcome())
                .as(
                        "first overlapping booking takes sameModelSub #2; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(response.bookings().get(0).substituteAssetId())
                .as(
                        "first booking must be assigned to substitute #2; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(2L);
        assertThat(response.bookings().get(1).outcome())
                .as(
                        "second overlapping booking must not reuse virtually allocated #2; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
        verify(reservationApi, never()).moveToAsset(anyLong(), anyLong(), anyLong(), any());
        verify(reservationApi, never()).forceCancel(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("simulate falls back to SameCategoryStrategy when SameModelStrategy has no free substitute")
    void simulate_fallsBackToSameCategoryStrategy() {
        AssetSummary sameCatSub = new AssetSummary(3L, "Scope 3", "TDS2000", 10L, 100L, AssetStatus.AVAILABLE);
        ReservationSummary booking = new ReservationSummary(
                11L, 501L, 1L, windowStart, windowStart.plusHours(2), ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(1L)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(1L, now)).thenReturn(List.of(booking));
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of());
        when(catalogApi.findAvailableByCategory(10L, 1L)).thenReturn(List.of(sameCatSub));
        when(reservationApi.isAssetFree(3L, booking.start(), booking.end(), 11L))
                .thenReturn(true);

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(1L, windowStart, windowEnd));

        assertThat(response.reassignedCount())
                .as(
                        "same-category substitute must satisfy fallback; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(1);
        assertThat(response.bookings().get(0).strategy())
                .as("fallback strategy name must be SameCategoryStrategy; see SameCategoryStrategy.name")
                .isEqualTo("SameCategoryStrategy");
        assertThat(response.bookings().get(0).substituteAssetId())
                .as(
                        "fallback booking must use same-category asset #3; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("simulate skips retired and busy substitutes and force-cancels when none remain")
    void simulate_skipsUnavailableOrBusySubstitutesAndForceCancels() {
        AssetSummary retiredSub = new AssetSummary(2L, "Scope Retired", "DS1054Z", 10L, 100L, AssetStatus.RETIRED);
        AssetSummary busySameCatSub = new AssetSummary(3L, "Scope Busy", "TDS2000", 10L, 100L, AssetStatus.AVAILABLE);
        ReservationSummary booking = new ReservationSummary(
                11L, 501L, 1L, windowStart, windowStart.plusHours(2), ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(1L)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(1L, now)).thenReturn(List.of(booking));
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of(retiredSub));
        when(catalogApi.findAvailableByCategory(10L, 1L)).thenReturn(List.of(retiredSub, busySameCatSub));
        when(reservationApi.isAssetFree(3L, booking.start(), booking.end(), 11L))
                .thenReturn(false);

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(1L, windowStart, windowEnd));

        assertThat(response.forceCancelledCount())
                .as(
                        "booking with only retired/busy substitutes must be FORCE_CANCELLED; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(1);
        assertThat(response.bookings().get(0).outcome())
                .as(
                        "outcome must be FORCE_CANCELLED when all candidates are skipped; see MaintenanceImpactSimulator.simulateSingleBooking")
                .isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
        assertThat(response.bookings().get(0).substituteAssetId())
                .as("force-cancelled booking must have null substituteAssetId")
                .isNull();
    }

    @Test
    @DisplayName("simulate computes weighted disruptionScore (1 per reassigned + 3 per force-cancelled)")
    void simulate_computesWeightedDisruptionScore() {
        AssetSummary sameModelSub = new AssetSummary(2L, "Scope 2", "DS1054Z", 10L, 100L, AssetStatus.AVAILABLE);
        ReservationSummary b1 = new ReservationSummary(
                11L, 501L, 1L, windowStart, windowStart.plusHours(1), ReservationStatus.CONFIRMED);
        ReservationSummary b2 = new ReservationSummary(
                12L, 502L, 1L, windowStart.plusHours(2), windowStart.plusHours(3), ReservationStatus.CONFIRMED);
        ReservationSummary b3 = new ReservationSummary(
                13L, 503L, 1L, windowStart.plusHours(2), windowStart.plusHours(4), ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(1L)).thenReturn(target);
        when(reservationApi.findUpcomingActiveByAsset(1L, now)).thenReturn(List.of(b1, b2, b3));
        when(catalogApi.findAvailableByModel("DS1054Z", 1L)).thenReturn(List.of(sameModelSub));
        when(catalogApi.findAvailableByCategory(10L, 1L)).thenReturn(List.of(sameModelSub));
        when(reservationApi.isAssetFree(2L, b1.start(), b1.end(), 11L)).thenReturn(true);
        when(reservationApi.isAssetFree(2L, b2.start(), b2.end(), 12L)).thenReturn(true);
        when(reservationApi.isAssetFree(2L, b3.start(), b3.end(), 13L)).thenReturn(false);

        MaintenanceImpactResponse response =
                simulator.simulate(new SimulateMaintenanceRequest(1L, windowStart, windowEnd));

        assertThat(response.reassignedCount())
                .as("two non-overlapping bookings must both be reassigned to #2")
                .isEqualTo(2);
        assertThat(response.forceCancelledCount())
                .as("third overlapping booking must be force-cancelled")
                .isEqualTo(1);
        assertThat(response.disruptionScore())
                .as("disruptionScore must equal 2*1 + 1*3 = 5; see MaintenanceImpactSimulator.simulate")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("simulate rejects maintenance windows where end is not after start")
    void simulate_rejectsInvalidWindow() {
        assertThatThrownBy(() ->
                        simulator.simulate(new SimulateMaintenanceRequest(1L, now.plusHours(2), now.plusHours(2))))
                .as(
                        "non-positive maintenance window must throw INVALID_TIME_RANGE; see MaintenanceImpactSimulator.simulate")
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("INVALID_TIME_RANGE");
    }

    @Test
    @DisplayName("simulate rejects maintenance windows exceeding 90 days")
    void simulate_rejectsWindowExceeding90Days() {
        assertThatThrownBy(
                        () -> simulator.simulate(new SimulateMaintenanceRequest(1L, now.plusDays(1), now.plusDays(92))))
                .as(
                        "maintenance window longer than 90 days must throw INVALID_TIME_RANGE; see MaintenanceImpactSimulator.simulate")
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("INVALID_TIME_RANGE");
    }
}

package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.InvalidParameterException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.AssetUtilizationItem;
import com.campus.reservation.web.HeatmapCell;
import com.campus.reservation.web.HeatmapResponse;
import com.campus.reservation.web.UtilizationAnalyticsResponse;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationAnalyticsServiceTest {

    // 2026-11-02T08:00:00Z -> 2026-11-02T10:00:00 Europe/Bucharest (Monday)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CatalogApi catalogApi;

    private ReservationAnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new ReservationAnalyticsService(reservationRepository, catalogApi, clock);
    }

    @Test
    @DisplayName("getUtilization: calculates booked hours, utilization, cancellation and no-show rates accurately")
    void getUtilization_calculatesMetricsCorrectly() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from =
                now.minusDays(5).withHour(8).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime to = now.withHour(20).withMinute(0).withSecond(0).withNano(0);

        // Realized booking: 2 hours on Asset 10
        Reservation r1 = new Reservation(
                42L, 10L, from.plusDays(1).withHour(10), from.plusDays(1).withHour(12), now.minusDays(10));
        ReflectionTestUtils.setField(r1, "id", 101L);
        ReflectionTestUtils.setField(r1, "status", ReservationStatus.RETURNED);

        // Cancelled booking on Asset 10
        Reservation r2 = new Reservation(
                42L, 10L, from.plusDays(2).withHour(14), from.plusDays(2).withHour(16), now.minusDays(10));
        ReflectionTestUtils.setField(r2, "id", 102L);
        ReflectionTestUtils.setField(r2, "status", ReservationStatus.CANCELLED);

        // No-show booking on Asset 10
        Reservation r3 = new Reservation(
                43L, 10L, from.plusDays(3).withHour(10), from.plusDays(3).withHour(12), now.minusDays(10));
        ReflectionTestUtils.setField(r3, "id", 103L);
        ReflectionTestUtils.setField(r3, "status", ReservationStatus.NO_SHOW);

        // Asset 20 has no realized bookings (idle)
        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r1, r2, r3));
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L, 20L));

        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Oscilloscope 1", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(20L))
                .thenReturn(new AssetSummary(20L, "Signal Generator 1", "DG1022Z", 1L, 1L, AssetStatus.AVAILABLE));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response)
                .as("analytics response must not be null; see ReservationAnalyticsService.getUtilization")
                .isNotNull();
        assertThat(response.reportingDays())
                .as("reporting days must be exactly 6; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(6);
        assertThat(response.totalCapacityHours())
                .as(
                        "total capacity hours must be 4 working days * 12h * 2 assets = 96.0; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(96.0);
        assertThat(response.averageUtilizationPercent())
                .as("average utilization must be 2.0 / 96.0 = 2.1%; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(2.1);
        assertThat(response.totalBookedHours())
                .as("total realized booked hours must equal 2.0; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(2.0);
        assertThat(response.totalBookingsCount())
                .as("total bookings count must equal 3; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(3);
        assertThat(response.cancellationRate())
                .as("cancellation rate must be 33.3%; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(33.3);
        assertThat(response.noShowRate())
                .as("no-show rate must be 33.3%; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(33.3);
        assertThat(response.totalAssetsTracked())
                .as("total assets tracked must be 2; see ReservationAnalyticsService.getUtilization")
                .isEqualTo(2);
        assertThat(response.idleAssetsCount())
                .as("idle assets count must be 1 (Asset 20); see ReservationAnalyticsService.getUtilization")
                .isEqualTo(1);

        assertThat(response.assets())
                .as("asset breakdown must contain 2 items; see ReservationAnalyticsService.getUtilization")
                .hasSize(2);
        assertThat(response.assets().get(0).assetId()).isEqualTo(10L);
        assertThat(response.assets().get(0).idle()).isFalse();
        assertThat(response.assets().get(1).assetId()).isEqualTo(20L);
        assertThat(response.assets().get(1).idle()).isTrue();
    }

    @Test
    @DisplayName("getUtilization: identifies capacity bottlenecks when simultaneous demand reaches 100% of model units")
    void getUtilization_identifiesCapacityBottlenecks() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from =
                now.minusDays(5).withHour(8).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime to = now.withHour(20).withMinute(0).withSecond(0).withNano(0);

        LocalDateTime slotStart = from.plusDays(1).withHour(10);
        LocalDateTime slotEnd = from.plusDays(1).withHour(12);

        // Model DS1054Z has 2 units (Asset 10 and Asset 11)
        // Two simultaneous bookings saturate the model (peak concurrency = 2 >= 2 units)
        Reservation r1 = new Reservation(42L, 10L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r1, "id", 101L);
        ReflectionTestUtils.setField(r1, "status", ReservationStatus.CONFIRMED);

        Reservation r2 = new Reservation(43L, 11L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r2, "id", 102L);
        ReflectionTestUtils.setField(r2, "status", ReservationStatus.CONFIRMED);

        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r1, r2));
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L, 11L));

        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Scope A", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(11L))
                .thenReturn(new AssetSummary(11L, "Scope B", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response.bottlenecks())
                .as(
                        "bottleneck detection must flag saturated DS1054Z model; see ReservationAnalyticsService.getUtilization")
                .hasSize(1);
        assertThat(response.bottlenecks().get(0).model()).isEqualTo("DS1054Z");
        assertThat(response.bottlenecks().get(0).peakConcurrentDemand()).isEqualTo(2);
        assertThat(response.bottlenecks().get(0).totalUnits()).isEqualTo(2);
        assertThat(response.bottlenecks().get(0).recommendation()).contains("full capacity");
    }

    @Test
    @DisplayName("getUtilization: rejects when from timestamp is strictly after to timestamp")
    void getUtilization_rejectsWhenFromAfterTo() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.plusDays(5);
        LocalDateTime to = now;

        assertThatThrownBy(() -> analyticsService.getUtilization(from, to))
                .as(
                        "from after to must throw InvalidParameterException; see ReservationAnalyticsService.getUtilization")
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("from must not be after to");
    }

    @Test
    @DisplayName("getHeatmap: generates full 7x12 grid with correct day-of-week and hourly intensity")
    void getHeatmap_populatesGridWithCorrectIntensity() {
        LocalDateTime now = LocalDateTime.now(clock); // Monday 10:00
        LocalDateTime from = now.withHour(8).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime to = from.plusDays(7);

        // Booking on Monday 10:00 to 12:00 (spans 10:00 and 11:00 slots)
        LocalDateTime bookingStart = from.withHour(10);
        LocalDateTime bookingEnd = from.withHour(12);

        Reservation r = new Reservation(42L, 10L, bookingStart, bookingEnd, now.minusDays(1));
        ReflectionTestUtils.setField(r, "id", 101L);
        ReflectionTestUtils.setField(r, "status", ReservationStatus.CONFIRMED);

        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r));

        HeatmapResponse heatmap = analyticsService.getHeatmap(from, to, null);

        assertThat(heatmap)
                .as("heatmap response must not be null; see ReservationAnalyticsService.getHeatmap")
                .isNotNull();
        assertThat(heatmap.totalBookingsSampled()).isEqualTo(1);
        assertThat(heatmap.peakHourlyDemand()).isEqualTo(1);
        assertThat(heatmap.cells())
                .as(
                        "grid must contain exactly 84 cells (7 days * 12 hours); see ReservationAnalyticsService.getHeatmap")
                .hasSize(84);

        // Monday is dayOfWeek = 1
        HeatmapCell mon10 = heatmap.cells().stream()
                .filter(c -> c.dayOfWeek() == 1 && c.hour() == 10)
                .findFirst()
                .orElseThrow();
        assertThat(mon10.bookingCount()).isEqualTo(1);
        assertThat(mon10.intensityScore()).isEqualTo(1.0);

        HeatmapCell mon11 = heatmap.cells().stream()
                .filter(c -> c.dayOfWeek() == 1 && c.hour() == 11)
                .findFirst()
                .orElseThrow();
        assertThat(mon11.bookingCount()).isEqualTo(1);
        assertThat(mon11.intensityScore()).isEqualTo(1.0);

        // Empty slot: Monday 14:00
        HeatmapCell mon14 = heatmap.cells().stream()
                .filter(c -> c.dayOfWeek() == 1 && c.hour() == 14)
                .findFirst()
                .orElseThrow();
        assertThat(mon14.bookingCount()).isEqualTo(0);
        assertThat(mon14.intensityScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("getHeatmap: filters demand for a specific asset ID when provided")
    void getHeatmap_filtersByAssetId() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.minusDays(3);
        LocalDateTime to = now;
        Long assetId = 55L;

        when(reservationRepository.findIntersectingByAssetId(eq(assetId), eq(from), eq(to)))
                .thenReturn(List.of());

        HeatmapResponse heatmap = analyticsService.getHeatmap(from, to, assetId);

        assertThat(heatmap.assetId()).isEqualTo(55L);
        assertThat(heatmap.totalBookingsSampled()).isEqualTo(0);
        assertThat(heatmap.peakHourlyDemand()).isEqualTo(0);
    }

    @Test
    @DisplayName("getUtilization: handles zero bookings gracefully without division by zero")
    void getUtilization_handlesEmptyBookingsAndZeroDemand() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.minusDays(7);
        LocalDateTime to = now;

        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of());
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L));
        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Scope A", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response.totalBookingsCount())
                .as("zero bookings must yield 0 count; see ReservationAnalyticsService.getUtilization")
                .isZero();
        assertThat(response.totalBookedHours()).isEqualTo(0.0);
        assertThat(response.cancellationRate()).isEqualTo(0.0);
        assertThat(response.noShowRate()).isEqualTo(0.0);
        assertThat(response.averageUtilizationPercent()).isEqualTo(0.0);
        assertThat(response.idleAssetsCount()).isEqualTo(1);
        assertThat(response.bottlenecks()).isEmpty();
    }

    @Test
    @DisplayName("getUtilization: sorts assets by realized booked hours in descending order")
    void getUtilization_sortsAssetsByBookedHoursDescending() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.minusDays(2);
        LocalDateTime to = now;

        // Asset 10: 2 hours
        Reservation r1 = new Reservation(42L, 10L, from.plusHours(2), from.plusHours(4), now.minusDays(5));
        ReflectionTestUtils.setField(r1, "id", 101L);
        ReflectionTestUtils.setField(r1, "status", ReservationStatus.CONFIRMED);

        // Asset 30: 5 hours
        Reservation r2 = new Reservation(43L, 30L, from.plusHours(1), from.plusHours(6), now.minusDays(5));
        ReflectionTestUtils.setField(r2, "id", 102L);
        ReflectionTestUtils.setField(r2, "status", ReservationStatus.RETURNED);

        // Asset 20: 0 hours (idle)
        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r1, r2));
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L, 20L, 30L));

        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Scope 10", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(20L))
                .thenReturn(new AssetSummary(20L, "Scope 20", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(30L))
                .thenReturn(new AssetSummary(30L, "Scope 30", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response.assets())
                .as("assets must be sorted by booked hours descending; see ReservationAnalyticsService.getUtilization")
                .extracting(AssetUtilizationItem::assetId)
                .containsExactly(30L, 10L, 20L);
    }

    @Test
    @DisplayName("getUtilization: defaults to last 30 days window when parameters are null")
    void getUtilization_defaultsToLast30DaysWhenNull() {
        LocalDateTime now = LocalDateTime.now(clock);

        when(reservationRepository.findIntersecting(now.minusDays(30), now)).thenReturn(List.of());
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of());

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(null, null);

        assertThat(response.from()).isEqualTo(now.minusDays(30));
        assertThat(response.to()).isEqualTo(now);
        assertThat(response.reportingDays()).isEqualTo(31);
    }

    @Test
    @DisplayName(
            "detectBottlenecks: sorts bottlenecks by peak demand descending and excludes sub-capacity or empty models")
    void detectBottlenecks_sortsAndExcludesSubCapacityModels() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.minusDays(5);
        LocalDateTime to = now;

        LocalDateTime slotStart = from.plusDays(1).withHour(10);
        LocalDateTime slotEnd = from.plusDays(1).withHour(12);

        // Model DS1054Z (2 units: 10, 11) - 3 concurrent requests (deficit 2, peakDemand 3)
        Reservation r1 = new Reservation(40L, 10L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r1, "id", 1L);
        ReflectionTestUtils.setField(r1, "status", ReservationStatus.CONFIRMED);

        Reservation r2 = new Reservation(41L, 11L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r2, "id", 2L);
        ReflectionTestUtils.setField(r2, "status", ReservationStatus.CONFIRMED);

        Reservation r3 = new Reservation(42L, 10L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r3, "id", 3L);
        ReflectionTestUtils.setField(r3, "status", ReservationStatus.CONFIRMED);

        // Model DG1022Z (2 units: 20, 21) - exactly 2 concurrent requests (deficit 1, peakDemand 2)
        Reservation r4 = new Reservation(43L, 20L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r4, "id", 4L);
        ReflectionTestUtils.setField(r4, "status", ReservationStatus.CHECKED_OUT);

        Reservation r5 = new Reservation(44L, 21L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r5, "id", 5L);
        ReflectionTestUtils.setField(r5, "status", ReservationStatus.CONFIRMED);

        // Model MULTI (2 units: 30, 31) - only 1 request (sub-capacity: peakDemand 1 < 2 units -> NOT a bottleneck)
        Reservation r6 = new Reservation(45L, 30L, slotStart, slotEnd, now.minusDays(10));
        ReflectionTestUtils.setField(r6, "id", 6L);
        ReflectionTestUtils.setField(r6, "status", ReservationStatus.CONFIRMED);

        // Model EMPTY (2 units: 40, 41) - 0 bookings (empty -> NOT a bottleneck)

        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r1, r2, r3, r4, r5, r6));
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L, 11L, 20L, 21L, 30L, 31L, 40L, 41L));

        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Scope A", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(11L))
                .thenReturn(new AssetSummary(11L, "Scope B", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(20L))
                .thenReturn(new AssetSummary(20L, "Gen A", "DG1022Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(21L))
                .thenReturn(new AssetSummary(21L, "Gen B", "DG1022Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(30L))
                .thenReturn(new AssetSummary(30L, "Multi A", "MULTI", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(31L))
                .thenReturn(new AssetSummary(31L, "Multi B", "MULTI", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(40L))
                .thenReturn(new AssetSummary(40L, "Empty A", "EMPTY", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.getAsset(41L))
                .thenReturn(new AssetSummary(41L, "Empty B", "EMPTY", 1L, 1L, AssetStatus.AVAILABLE));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response.bottlenecks())
                .as("bottlenecks must only contain saturated models, ordered by peak demand descending")
                .hasSize(2);

        assertThat(response.bottlenecks().get(0).model()).isEqualTo("DS1054Z");
        assertThat(response.bottlenecks().get(0).peakConcurrentDemand()).isEqualTo(3);
        assertThat(response.bottlenecks().get(0).peakUtilizationRate()).isEqualTo(150.0);
        assertThat(response.bottlenecks().get(0).recommendation()).contains("Recommend acquiring 1 additional unit(s)");

        assertThat(response.bottlenecks().get(1).model()).isEqualTo("DG1022Z");
        assertThat(response.bottlenecks().get(1).peakConcurrentDemand()).isEqualTo(2);
        assertThat(response.bottlenecks().get(1).peakUtilizationRate()).isEqualTo(100.0);
        assertThat(response.bottlenecks().get(1).recommendation()).contains("full capacity");
    }

    @Test
    @DisplayName("getHeatmap: calculates proportional intensity scores and excludes hours outside 08:00 to 20:00")
    void getHeatmap_calculatesProportionalIntensityAndIgnoresOffHours() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.withHour(8).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime to = from.plusDays(7);

        // Tuesday (dayOfWeek = 2)
        LocalDateTime tue = from.plusDays(1);

        // Slot 1: Tue 10:00 to 11:00 (2 bookings -> peak = 2)
        Reservation r1 = new Reservation(40L, 10L, tue.withHour(10), tue.withHour(11), now.minusDays(1));
        ReflectionTestUtils.setField(r1, "id", 1L);
        ReflectionTestUtils.setField(r1, "status", ReservationStatus.CONFIRMED);

        Reservation r2 = new Reservation(41L, 11L, tue.withHour(10), tue.withHour(11), now.minusDays(1));
        ReflectionTestUtils.setField(r2, "id", 2L);
        ReflectionTestUtils.setField(r2, "status", ReservationStatus.RETURNED);

        // Slot 2: Tue 14:00 to 15:00 (1 booking -> intensity = 0.5)
        Reservation r3 = new Reservation(42L, 10L, tue.withHour(14), tue.withHour(15), now.minusDays(1));
        ReflectionTestUtils.setField(r3, "id", 3L);
        ReflectionTestUtils.setField(r3, "status", ReservationStatus.CHECKED_OUT);

        // Off-hours: 06:00 to 08:00 (before open) and 20:00 to 22:00 (after close)
        Reservation rEarly = new Reservation(43L, 10L, tue.withHour(6), tue.withHour(8), now.minusDays(1));
        ReflectionTestUtils.setField(rEarly, "id", 4L);
        ReflectionTestUtils.setField(rEarly, "status", ReservationStatus.CONFIRMED);

        Reservation rLate = new Reservation(44L, 10L, tue.withHour(20), tue.withHour(22), now.minusDays(1));
        ReflectionTestUtils.setField(rLate, "id", 5L);
        ReflectionTestUtils.setField(rLate, "status", ReservationStatus.CONFIRMED);

        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r1, r2, r3, rEarly, rLate));

        HeatmapResponse heatmap = analyticsService.getHeatmap(from, to, null);

        assertThat(heatmap.peakHourlyDemand()).isEqualTo(2);

        HeatmapCell tue10 = heatmap.cells().stream()
                .filter(c -> c.dayOfWeek() == 2 && c.hour() == 10)
                .findFirst()
                .orElseThrow();
        assertThat(tue10.bookingCount()).isEqualTo(2);
        assertThat(tue10.intensityScore()).isEqualTo(1.0);

        HeatmapCell tue14 = heatmap.cells().stream()
                .filter(c -> c.dayOfWeek() == 2 && c.hour() == 14)
                .findFirst()
                .orElseThrow();
        assertThat(tue14.bookingCount()).isEqualTo(1);
        assertThat(tue14.intensityScore()).isEqualTo(0.5);

        // All 84 cells must be within hours 8..19
        assertThat(heatmap.cells()).allMatch(c -> c.hour() >= 8 && c.hour() < 20);
    }

    @Test
    @DisplayName("getUtilization: excludes RETIRED assets and their booking hours from active utilization metrics")
    void getUtilization_skipsRetiredAssetsAndTheirHours() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.minusDays(5);
        LocalDateTime to = now;

        // Booking on operational asset 10: 2 hours
        Reservation r10 = new Reservation(
                42L, 10L, from.plusDays(1).withHour(10), from.plusDays(1).withHour(12), now.minusDays(10));
        ReflectionTestUtils.setField(r10, "id", 101L);
        ReflectionTestUtils.setField(r10, "status", ReservationStatus.CONFIRMED);

        // Historical booking on retired asset 88: 4 hours
        Reservation r88 = new Reservation(
                43L, 88L, from.plusDays(2).withHour(10), from.plusDays(2).withHour(14), now.minusDays(10));
        ReflectionTestUtils.setField(r88, "id", 102L);
        ReflectionTestUtils.setField(r88, "status", ReservationStatus.RETURNED);

        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L, 88L));
        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r10, r88));

        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Scope 10", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        // Asset 88 was retired
        when(catalogApi.getAsset(88L))
                .thenReturn(new AssetSummary(88L, "Decommissioned Scope", "OLD_MODEL", 1L, 1L, AssetStatus.RETIRED));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response.totalAssetsTracked())
                .as("retired asset 88 must be excluded from active equipment fleet")
                .isEqualTo(1);
        assertThat(response.assets()).extracting(AssetUtilizationItem::assetId).containsExactly(10L);
        assertThat(response.totalBookedHours())
                .as("total booked hours must only sum operational assets (2.0h), excluding retired asset 88 (4.0h)")
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName(
            "detectBottlenecks: contiguous back-to-back bookings (08-09, 09-10) count as 1 saturated period, not 2")
    void detectBottlenecks_mergesContiguousBookingsIntoSingleSaturatedPeriod() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 2, 8, 0); // Monday 08:00
        LocalDateTime to = LocalDateTime.of(2026, 11, 2, 20, 0); // Monday 20:00

        // Single available unit for model "DS1054Z" (Asset 10)
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L));
        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Scope 10", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        // Res 1: 08:00 - 09:00
        Reservation r1 = new Reservation(42L, 10L, from.withHour(8), from.withHour(9), from.minusDays(1));
        ReflectionTestUtils.setField(r1, "id", 101L);
        ReflectionTestUtils.setField(r1, "status", ReservationStatus.CONFIRMED);

        // Res 2: 09:00 - 10:00 (contiguous with Res 1 at 09:00)
        Reservation r2 = new Reservation(43L, 10L, from.withHour(9), from.withHour(10), from.minusDays(1));
        ReflectionTestUtils.setField(r2, "id", 102L);
        ReflectionTestUtils.setField(r2, "status", ReservationStatus.CONFIRMED);

        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r1, r2));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response.bottlenecks())
                .as("model DS1054Z must be detected as bottleneck; see ReservationAnalyticsService.getUtilization")
                .hasSize(1);
        assertThat(response.bottlenecks().get(0).peakConcurrentDemand())
                .as("peak demand should be 1 unit")
                .isEqualTo(1);
        assertThat(response.bottlenecks().get(0).bottleneckHoursCount())
                .as(
                        "contiguous bookings 08:00-09:00 and 09:00-10:00 must be counted as 1 continuous saturated period, not 2")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("getUtilization: computes exact operating hours for partial day windows")
    void getUtilization_calculatesPartialDayOperatingHoursAccurately() {
        // Wednesday 14:00 to 18:00 (4 hours within lab operating window 08:00-20:00)
        LocalDateTime from = LocalDateTime.of(2026, 11, 4, 14, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 4, 18, 0);

        Reservation r = new Reservation(42L, 10L, from, from.plusHours(2), from.minusDays(1));
        ReflectionTestUtils.setField(r, "id", 201L);
        ReflectionTestUtils.setField(r, "status", ReservationStatus.CONFIRMED);

        when(reservationRepository.findIntersecting(from, to)).thenReturn(List.of(r));
        when(reservationRepository.findDistinctAssetIds()).thenReturn(List.of(10L));
        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Scope 10", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        UtilizationAnalyticsResponse response = analyticsService.getUtilization(from, to);

        assertThat(response.totalCapacityHours())
                .as("partial 4-hour window on 1 asset must yield exactly 4.0 capacity hours, not a full 12h day")
                .isEqualTo(4.0);
        assertThat(response.totalBookedHours())
                .as("2-hour reservation within 4-hour window must give 2.0 booked hours")
                .isEqualTo(2.0);
        assertThat(response.averageUtilizationPercent())
                .as("2.0 / 4.0 must equal 50.0% utilization")
                .isEqualTo(50.0);
    }
}

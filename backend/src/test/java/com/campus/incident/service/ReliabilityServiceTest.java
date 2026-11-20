package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.NotFoundException;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.AssetReliabilityItem;
import com.campus.incident.web.ReliabilityLevel;
import com.campus.incident.web.ReliabilityReportResponse;
import com.campus.reservation.api.ReservationApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReliabilityServiceTest {

    // 08:00 UTC = 10:00 campus time
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0);
    private static final LocalDateTime FROM_180 = NOW.minusDays(180);
    private static final LocalDateTime REPORTED = NOW.minusDays(10);

    @Mock
    private IncidentReportRepository incidentReportRepository;

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private CatalogApi catalogApi;

    private ReliabilityService service;

    @BeforeEach
    void setUp() {
        service = new ReliabilityService(incidentReportRepository, reservationApi, catalogApi, CLOCK);
    }

    @Test
    void windowEndsNowAndStartsDaysBefore() {
        when(incidentReportRepository.findByCreatedAtGreaterThanEqual(NOW.minusDays(90)))
                .thenReturn(List.of());
        when(reservationApi.usedMinutesByAsset(NOW.minusDays(90), NOW)).thenReturn(Map.of());

        ReliabilityReportResponse report = service.report(90);

        verify(incidentReportRepository).findByCreatedAtGreaterThanEqual(NOW.minusDays(90));
        verify(reservationApi).usedMinutesByAsset(NOW.minusDays(90), NOW);
        assertThat(report.windowDays())
                .as("echoes the window; see ReliabilityService.report")
                .isEqualTo(90);
        assertThat(report.from()).as("now − days").isEqualTo(NOW.minusDays(90));
        assertThat(report.to()).as("now from the Clock").isEqualTo(NOW);
    }

    @Test
    void mapsIncidentsAndUsageIntoTheCalculator() {
        givenWindow(
                List.of(incident(1L, IncidentSeverity.LOW), incident(1L, IncidentSeverity.MEDIUM)), Map.of(1L, 600L));
        givenAsset(1L, "DS1054Z");

        List<AssetReliabilityItem> items = service.report(180).items();

        assertThat(items).as("one unit with failures").hasSize(1);
        assertThat(items.get(0).failures()).as("both incidents are failures").isEqualTo(2);
        // 600 min / 60
        assertThat(items.get(0).usageHours()).as("Calin's minutes → hours").isEqualTo(new BigDecimal("10.0"));
        assertThat(items.get(0).assetName()).as("name from the catalog").isEqualTo("DS1054Z #1");
    }

    @Test
    void rejectedStatusIsMappedToRejected() {
        IncidentReport rejected = incident(1L, IncidentSeverity.HIGH);
        rejected.reject(7L, "works fine", REPORTED.plusHours(1));
        givenWindow(List.of(rejected), Map.of(1L, 600L));
        givenAsset(1L, "DS1054Z");

        assertThat(service.report(180).items())
                .as("a REJECTED report is not a failure")
                .isEmpty();
    }

    @Test
    void highAndCriticalAreMappedToSevere() {
        givenWindow(
                List.of(
                        incident(1L, IncidentSeverity.LOW),
                        incident(1L, IncidentSeverity.MEDIUM),
                        incident(1L, IncidentSeverity.HIGH),
                        incident(1L, IncidentSeverity.CRITICAL)),
                Map.of());
        givenAsset(1L, "DS1054Z");

        AssetReliabilityItem item = service.report(180).items().get(0);

        assertThat(item.failures()).as("all four").isEqualTo(4);
        assertThat(item.severeFailures()).as("HIGH + CRITICAL").isEqualTo(2);
    }

    @Test
    void onlyResolvedIncidentsHaveARepairTime() {
        IncidentReport resolved = incident(1L, IncidentSeverity.HIGH);
        resolved.resolve(7L, "fixed", REPORTED.plusHours(6));
        givenWindow(List.of(resolved, incident(1L, IncidentSeverity.LOW)), Map.of(1L, 600L));
        givenAsset(1L, "DS1054Z");

        // only the resolved one: 6 h
        assertThat(service.report(180).items().get(0).avgRepairHours())
                .as("resolvedAt is passed only for RESOLVED")
                .isEqualTo(new BigDecimal("6.0"));
    }

    @Test
    void unitWithUseButNoIncidentStillCountsForItsModel() {
        // #1: 2 failures / 40 h → 5.0; twin #2 was used 60 h without any incident, twin #3 failed once in 60 h
        givenWindow(
                List.of(
                        incident(1L, IncidentSeverity.LOW),
                        incident(1L, IncidentSeverity.LOW),
                        incident(3L, IncidentSeverity.LOW)),
                Map.of(1L, 40L * 60, 2L, 60L * 60, 3L, 60L * 60));
        givenAsset(1L, "DS1054Z");
        givenAsset(2L, "DS1054Z");
        givenAsset(3L, "DS1054Z");

        List<AssetReliabilityItem> items = service.report(180).items();

        // twins #2 + #3: 1 failure / 120 h → 0.83; 5.0 / 0.83 = 6.0
        assertThat(items)
                .as("#2 never failed, so it is not listed")
                .extracting(AssetReliabilityItem::assetId)
                .containsExactly(1L, 3L);
        assertThat(items.get(0).vsModel()).as("#2's hours are in #1's baseline").isEqualTo(new BigDecimal("6.0"));
        assertThat(items.get(0).level()).as("lemon").isEqualTo(ReliabilityLevel.INSPECT);
        verify(catalogApi).getAsset(2L);
    }

    @Test
    void unknownAssetIsSkipped() {
        givenWindow(List.of(incident(99L, IncidentSeverity.HIGH), incident(1L, IncidentSeverity.LOW)), Map.of());
        when(catalogApi.getAsset(99L)).thenThrow(new NotFoundException("ASSET_NOT_FOUND", "Asset not found"));
        givenAsset(1L, "DS1054Z");

        List<AssetReliabilityItem> items = service.report(180).items();

        assertThat(items)
                .as("the rest of the report is still built")
                .extracting(AssetReliabilityItem::assetId)
                .containsExactly(1L);
    }

    @Test
    void asksTheCatalogOncePerUnit() {
        givenWindow(
                List.of(
                        incident(1L, IncidentSeverity.LOW),
                        incident(1L, IncidentSeverity.LOW),
                        incident(1L, IncidentSeverity.LOW)),
                Map.of(1L, 600L));
        givenAsset(1L, "DS1054Z");

        service.report(180);

        verify(catalogApi, times(1)).getAsset(anyLong());
    }

    @Test
    void noDataGivesAnEmptyReport() {
        givenWindow(List.of(), Map.of());

        assertThat(service.report(180).items()).as("nothing to rank").isEmpty();
    }

    private void givenWindow(List<IncidentReport> reports, Map<Long, Long> usedMinutes) {
        when(incidentReportRepository.findByCreatedAtGreaterThanEqual(FROM_180)).thenReturn(reports);
        when(reservationApi.usedMinutesByAsset(FROM_180, NOW)).thenReturn(usedMinutes);
    }

    private void givenAsset(long id, String model) {
        when(catalogApi.getAsset(id))
                .thenReturn(new AssetSummary(id, model + " #" + id, model, 1L, 1L, AssetStatus.AVAILABLE));
    }

    private static IncidentReport incident(long assetId, IncidentSeverity severity) {
        return new IncidentReport(assetId, null, 5L, "broken", severity, null, REPORTED);
    }
}

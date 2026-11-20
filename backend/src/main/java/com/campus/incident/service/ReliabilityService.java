package com.campus.incident.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.NotFoundException;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.ReliabilityReportResponse;
import com.campus.reservation.api.ReservationApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the equipment reliability report (#291): reads the data, {@link ReliabilityCalculator} does the maths.
 * Nothing is stored; every call calculates from live data.
 */
@Service
public class ReliabilityService {

    private final IncidentReportRepository incidentReportRepository;
    private final ReservationApi reservationApi;
    private final CatalogApi catalogApi;
    private final Clock clock;

    public ReliabilityService(
            IncidentReportRepository incidentReportRepository,
            ReservationApi reservationApi,
            CatalogApi catalogApi,
            Clock clock) {
        this.incidentReportRepository = incidentReportRepository;
        this.reservationApi = reservationApi;
        this.catalogApi = catalogApi;
        this.clock = clock;
    }

    /** @param days length of the window ending now (the controller allows 30 to 365) */
    @Transactional(readOnly = true)
    public ReliabilityReportResponse report(int days) {
        LocalDateTime to = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime from = to.minusDays(days);

        List<IncidentReport> reports = incidentReportRepository.findByCreatedAtGreaterThanEqual(from);
        // Calin's read (#298): minutes each asset was really checked out in [from, to].
        Map<Long, Long> usedMinutes = reservationApi.usedMinutesByAsset(from, to);

        // Units that were used without any incident still matter: they are the twins a lemon is compared with.
        Set<Long> assetIds = new TreeSet<>(usedMinutes.keySet());
        reports.forEach(r -> assetIds.add(r.getAssetId()));

        // One catalog lookup per unit: the accepted limit of spec §8 (tens of units in a lab). A bulk read on
        // CatalogApi (Liviu) would make this one query.
        List<UnitInput> units = new ArrayList<>();
        for (Long assetId : assetIds) {
            try {
                AssetSummary asset = catalogApi.getAsset(assetId);
                units.add(new UnitInput(
                        assetId,
                        asset.name(),
                        asset.model(),
                        asset.status().name(),
                        usedMinutes.getOrDefault(assetId, 0L)));
            } catch (NotFoundException e) {
                // The catalog no longer knows the asset: skip it instead of failing the whole page.
            }
        }

        List<IncidentInput> incidents =
                reports.stream().map(ReliabilityService::toInput).toList();
        return new ReliabilityReportResponse(days, from, to, ReliabilityCalculator.rank(units, incidents, days));
    }

    private static IncidentInput toInput(IncidentReport report) {
        return new IncidentInput(
                report.getAssetId(),
                report.getStatus() == IncidentStatus.REJECTED,
                report.getSeverity().takesAssetOutOfUseAtOnce(),
                report.getCreatedAt(),
                report.getStatus() == IncidentStatus.RESOLVED ? report.getResolvedAt() : null);
    }
}

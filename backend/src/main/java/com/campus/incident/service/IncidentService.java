package com.campus.incident.service;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.CreateIncidentRequest;
import com.campus.incident.web.IncidentResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IncidentService {

    /** Incidents that still keep their asset out of use. */
    private static final List<IncidentStatus> ACTIVE_STATUSES = List.of(IncidentStatus.OPEN, IncidentStatus.IN_REPAIR);

    private final IncidentReportRepository incidentReportRepository;
    private final CatalogApi catalogApi;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public IncidentService(
            IncidentReportRepository incidentReportRepository,
            CatalogApi catalogApi,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.incidentReportRepository = incidentReportRepository;
        this.catalogApi = catalogApi;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Saves the report and takes the asset out of use in the same transaction. getAsset throws 404
     * ASSET_NOT_FOUND for an unknown id, markUnderMaintenance throws 409 ASSET_RETIRED for a retired asset.
     * Calin's reassignment engine reacts to the event after the commit, when the asset is already
     * UNDER_MAINTENANCE, so it can never be picked as its own substitute.
     */
    public IncidentResponse reportIncident(CreateIncidentRequest request, Long reporterId) {
        catalogApi.getAsset(request.assetId());

        IncidentReport report = new IncidentReport(
                request.assetId(), null, reporterId, request.description(), request.severity(), null, now());
        IncidentReport saved = incidentReportRepository.save(report);

        catalogApi.markUnderMaintenance(saved.getAssetId());

        eventPublisher.publishEvent(
                new AssetDamagedEvent(saved.getId(), saved.getAssetId(), reporterId, clock.instant()));

        return toResponse(saved);
    }

    /**
     * OPEN to IN_REPAIR. Starting twice returns the incident unchanged. The asset stays UNDER_MAINTENANCE,
     * so nothing changes in the catalog. From RESOLVED or REJECTED: 409 INVALID_INCIDENT_STATUS_TRANSITION.
     */
    public IncidentResponse startRepair(Long incidentId) {
        IncidentReport report = findOrThrow(incidentId);

        if (report.getStatus() == IncidentStatus.IN_REPAIR) {
            return toResponse(report);
        }

        report.startRepair();
        return toResponse(incidentReportRepository.save(report));
    }

    /**
     * Resolves an OPEN or IN_REPAIR incident. Resolving twice returns the incident unchanged. The asset
     * becomes AVAILABLE again only when no other OPEN or IN_REPAIR incident is left on it.
     */
    public IncidentResponse resolveIncident(Long incidentId, String resolutionNotes, Long adminId) {
        IncidentReport report = findOrThrow(incidentId);

        if (report.getStatus() == IncidentStatus.RESOLVED) {
            return toResponse(report);
        }

        report.resolve(adminId, resolutionNotes, now());
        IncidentReport updated = incidentReportRepository.save(report);
        releaseAssetIfNoActiveIncident(updated.getAssetId());

        return toResponse(updated);
    }

    /**
     * Rejects an OPEN or IN_REPAIR incident (the report was wrong). Rejecting twice returns it unchanged.
     * Same asset rule as resolve. Bookings the engine already moved stay where they are.
     */
    public IncidentResponse rejectIncident(Long incidentId, String resolutionNotes, Long adminId) {
        IncidentReport report = findOrThrow(incidentId);

        if (report.getStatus() == IncidentStatus.REJECTED) {
            return toResponse(report);
        }

        report.reject(adminId, resolutionNotes, now());
        IncidentReport updated = incidentReportRepository.save(report);
        releaseAssetIfNoActiveIncident(updated.getAssetId());

        return toResponse(updated);
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> getAllIncidents(IncidentStatus status, Pageable pageable) {
        Page<IncidentReport> page = (status != null)
                ? incidentReportRepository.findByStatus(status, pageable)
                : incidentReportRepository.findAll(pageable);

        return PageResponse.from(page, this::toResponse);
    }

    /** The incidents this user reported (My incidents page). */
    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> getMyIncidents(Long reporterId, Pageable pageable) {
        return PageResponse.from(incidentReportRepository.findByReportedById(reporterId, pageable), this::toResponse);
    }

    /**
     * One incident, for its reporter or an admin. Anyone else gets the same 404 as for a missing id, so
     * nobody can find out which incident ids exist.
     */
    @Transactional(readOnly = true)
    public IncidentResponse getIncident(Long incidentId, Long requesterId, boolean requesterIsAdmin) {
        IncidentReport report = findOrThrow(incidentId);

        if (!requesterIsAdmin && !report.getReportedById().equals(requesterId)) {
            throw notFound(incidentId);
        }
        return toResponse(report);
    }

    private IncidentReport findOrThrow(Long incidentId) {
        return incidentReportRepository.findById(incidentId).orElseThrow(() -> notFound(incidentId));
    }

    private static NotFoundException notFound(Long incidentId) {
        return new NotFoundException("INCIDENT_NOT_FOUND", "Incident report not found with id: " + incidentId);
    }

    // After resolve or reject: the asset is usable again only if no other incident still keeps it out of use.
    private void releaseAssetIfNoActiveIncident(Long assetId) {
        if (incidentReportRepository
                .findByAssetIdAndStatusIn(assetId, ACTIVE_STATUSES)
                .isEmpty()) {
            catalogApi.markAvailable(assetId);
        }
    }

    // Whole seconds, like the contract format (2026-11-03T14:00:00), so POST and GET return the same value.
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private IncidentResponse toResponse(IncidentReport report) {
        return new IncidentResponse(
                report.getId(),
                report.getAssetId(),
                report.getReservationId(),
                report.getReportedById(),
                report.getDescription(),
                report.getSeverity(),
                report.getStatus(),
                report.getPhotoKey(),
                report.getCreatedAt(),
                report.getResolvedAt(),
                report.getResolvedById(),
                report.getResolutionNotes());
    }
}

package com.campus.incident.service;

import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.incident.api.AssetRepairedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.CreateIncidentRequest;
import com.campus.incident.web.IncidentResponse;
import com.campus.incident.web.ResolveIncidentRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IncidentService {

    private final IncidentReportRepository incidentReportRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public IncidentService(
            IncidentReportRepository incidentReportRepository, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.incidentReportRepository = incidentReportRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public IncidentResponse reportIncident(CreateIncidentRequest request, Long reporterId) {
        IncidentReport report = new IncidentReport(
                request.assetId(),
                null,
                reporterId,
                request.description(),
                request.severity(),
                null,
                LocalDateTime.now(clock));

        IncidentReport saved = incidentReportRepository.save(report);

        eventPublisher.publishEvent(
                new AssetDamagedEvent(saved.getId(), saved.getAssetId(), reporterId, clock.instant()));

        return toResponse(saved);
    }

    public IncidentResponse resolveIncident(Long incidentId, ResolveIncidentRequest request, Long adminId) {
        IncidentReport report = incidentReportRepository
                .findById(incidentId)
                .orElseThrow(() -> new NotFoundException(
                        "INCIDENT_NOT_FOUND", "Incident report not found with id: " + incidentId));

        if (report.getStatus() == IncidentStatus.RESOLVED) {
            return toResponse(report);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        report.resolve(adminId, request.resolutionNotes(), now);
        IncidentReport updated = incidentReportRepository.save(report);

        eventPublisher.publishEvent(new AssetRepairedEvent(updated.getId(), updated.getAssetId(), adminId, now));

        return toResponse(updated);
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> getAllIncidents(IncidentStatus status, Pageable pageable) {
        Page<IncidentReport> page = (status != null)
                ? incidentReportRepository.findByStatus(status, pageable)
                : incidentReportRepository.findAll(pageable);

        return PageResponse.from(page, this::toResponse);
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

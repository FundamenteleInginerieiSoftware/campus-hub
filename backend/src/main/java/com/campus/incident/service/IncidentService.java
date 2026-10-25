package com.campus.incident.service;

import com.campus.common.security.CurrentUser;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.incident.api.AssetRepairedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.CreateIncidentRequest;
import com.campus.incident.web.IncidentResponse;
import com.campus.incident.web.ResolveIncidentRequest;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
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

    public IncidentResponse reportIncident(CreateIncidentRequest request, CurrentUser currentUser) {
        LocalDateTime now = LocalDateTime.now(clock);

        IncidentReport report = new IncidentReport();
        report.setAssetId(request.assetId());
        report.setReportedById(currentUser.id());
        report.setReservationId(request.reservationId());
        report.setDescription(request.description());
        report.setSeverity(request.severity());
        report.setPhotoKey(request.photoKey());
        report.setStatus(IncidentStatus.OPEN);
        report.setCreatedAt(now);

        IncidentReport saved = incidentReportRepository.save(report);

        eventPublisher.publishEvent(
                new AssetDamagedEvent(saved.getId(), saved.getAssetId(), saved.getReportedById(), now));

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> getAllIncidents(IncidentStatus status) {
        List<IncidentReport> reports;
        if (status != null) {
            reports = incidentReportRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            reports = incidentReportRepository.findAllByOrderByCreatedAtDesc();
        }
        return reports.stream().map(this::toResponse).toList();
    }

    public IncidentResponse resolveIncident(Long incidentId, ResolveIncidentRequest request, CurrentUser adminUser) {
        IncidentReport report = incidentReportRepository
                .findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("Incident report not found with id: " + incidentId));

        if (report.getStatus() == IncidentStatus.RESOLVED) {
            return toResponse(report);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        report.setStatus(IncidentStatus.RESOLVED);
        report.setResolvedAt(now);
        report.setResolvedById(adminUser.id());
        if (request != null && request.resolutionNotes() != null) {
            report.setResolutionNote(request.resolutionNotes());
        }

        IncidentReport updated = incidentReportRepository.save(report);

        // markAvailable action via domain event for modular architecture
        eventPublisher.publishEvent(
                new AssetRepairedEvent(updated.getId(), updated.getAssetId(), updated.getResolvedById(), now));

        return toResponse(updated);
    }

    private IncidentResponse toResponse(IncidentReport report) {
        return new IncidentResponse(
                report.getId(),
                report.getAssetId(),
                report.getReportedById(),
                report.getReservationId(),
                report.getDescription(),
                report.getSeverity(),
                report.getPhotoKey(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getResolvedAt(),
                report.getResolvedById(),
                report.getResolutionNote());
    }
}

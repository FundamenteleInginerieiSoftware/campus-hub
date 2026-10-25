package com.campus.incident.service;

import com.campus.common.security.CurrentUser;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.CreateIncidentRequest;
import com.campus.incident.web.IncidentResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentReportRepository incidentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public IncidentResponse reportIncident(CreateIncidentRequest request, CurrentUser currentUser) {
        // 1. Creăm entitatea JPA pe baza datelor primite și a utilizatorului logat
        IncidentReport incident = new IncidentReport();
        incident.setAssetId(request.assetId());
        incident.setReportedById(currentUser.id());
        incident.setReservationId(request.reservationId());
        incident.setDescription(request.description());
        incident.setSeverity(request.severity());
        incident.setPhotoKey(request.photoKey());
        incident.setStatus(IncidentStatus.OPEN);
        incident.setCreatedAt(LocalDateTime.now());

        // 2. Salvăm incidentul în baza de date PostgreSQL
        IncidentReport savedIncident = incidentRepository.save(incident);

        // 3. Publicăm evenimentul public AssetDamagedEvent pentru celelalte module
        AssetDamagedEvent event = new AssetDamagedEvent(
                savedIncident.getId(),
                savedIncident.getAssetId(),
                savedIncident.getReportedById(),
                savedIncident.getCreatedAt());
        eventPublisher.publishEvent(event);

        // 4. Returnăm DTO-ul de răspuns către controler
        return IncidentResponse.fromEntity(savedIncident);
    }
}

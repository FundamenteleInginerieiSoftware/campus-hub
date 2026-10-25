package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.common.security.CurrentUser;
import com.campus.incident.api.AssetRepairedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.IncidentResponse;
import com.campus.incident.web.ResolveIncidentRequest;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock
    private IncidentReportRepository incidentReportRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private Clock clock;
    private IncidentService incidentService;
    private CurrentUser adminUser;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("UTC"));
        incidentService = new IncidentService(incidentReportRepository, eventPublisher, clock);
        adminUser = new CurrentUser(99L, "admin@campus.local", Set.of("ROLE_ADMIN"));
    }

    @Test
    void resolveIncident_shouldResolveAndPublishEvent_whenOpen() {
        Long incidentId = 1L;
        IncidentReport existingReport = new IncidentReport();
        existingReport.setId(incidentId);
        existingReport.setAssetId(10L);
        existingReport.setStatus(IncidentStatus.OPEN);
        existingReport.setCreatedAt(LocalDateTime.now(clock).minusDays(1));

        when(incidentReportRepository.findById(incidentId)).thenReturn(Optional.of(existingReport));
        when(incidentReportRepository.save(any(IncidentReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResolveIncidentRequest request = new ResolveIncidentRequest("Replaced display panel");

        IncidentResponse response = incidentService.resolveIncident(incidentId, request, adminUser);

        assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(response.resolvedById()).isEqualTo(adminUser.id());
        assertThat(response.resolvedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(response.resolutionNotes()).isEqualTo("Replaced display panel");

        ArgumentCaptor<AssetRepairedEvent> eventCaptor = ArgumentCaptor.forClass(AssetRepairedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AssetRepairedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.incidentId()).isEqualTo(incidentId);
        assertThat(publishedEvent.assetId()).isEqualTo(10L);
        assertThat(publishedEvent.repairedById()).isEqualTo(adminUser.id());
    }

    @Test
    void resolveIncident_shouldThrowEntityNotFound_whenMissing() {
        Long missingId = 404L;
        when(incidentReportRepository.findById(missingId)).thenReturn(Optional.empty());

        ResolveIncidentRequest request = new ResolveIncidentRequest("Fixed");

        assertThatThrownBy(() -> incidentService.resolveIncident(missingId, request, adminUser))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Incident report not found with id: " + missingId);
    }

    @Test
    void getAllIncidents_shouldFilterByStatusAndMapToResponse() {
        IncidentReport report = new IncidentReport();
        report.setId(5L);
        report.setAssetId(20L);
        report.setStatus(IncidentStatus.OPEN);
        report.setCreatedAt(LocalDateTime.now(clock));

        when(incidentReportRepository.findByStatusOrderByCreatedAtDesc(IncidentStatus.OPEN))
                .thenReturn(List.of(report));

        List<IncidentResponse> results = incidentService.getAllIncidents(IncidentStatus.OPEN);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(5L);
        assertThat(results.get(0).status()).isEqualTo(IncidentStatus.OPEN);
    }
}

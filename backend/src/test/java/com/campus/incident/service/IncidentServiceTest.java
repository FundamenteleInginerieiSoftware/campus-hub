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
import com.campus.incident.domain.IncidentReport.Severity;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock
    private IncidentReportRepository incidentReportRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("UTC"));

    private IncidentService incidentService;

    private CurrentUser adminUser;

    @BeforeEach
    void setUp() {
        incidentService = new IncidentService(incidentReportRepository, eventPublisher, clock);
        adminUser = new CurrentUser(99L, "admin@campus.com", Set.of("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("resolveIncident - happy path updates status and publishes AssetRepairedEvent")
    void resolveIncident_happyPath() {
        Long incidentId = 1L;
        IncidentReport existingReport = new IncidentReport();
        existingReport.setId(incidentId);
        existingReport.setAssetId(42L);
        existingReport.setReportedById(10L);
        existingReport.setDescription("Broken screen");
        existingReport.setSeverity(Severity.HIGH);
        existingReport.setStatus(IncidentStatus.OPEN);
        existingReport.setCreatedAt(LocalDateTime.now(clock).minusDays(1));

        when(incidentReportRepository.findById(incidentId)).thenReturn(Optional.of(existingReport));
        when(incidentReportRepository.save(any(IncidentReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResolveIncidentRequest request = new ResolveIncidentRequest("Replaced display panel");

        IncidentResponse response = incidentService.resolveIncident(incidentId, request, adminUser);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(response.resolvedById()).isEqualTo(99L);
        assertThat(response.resolutionNote()).isEqualTo("Replaced display panel");
        assertThat(response.resolvedAt()).isNotNull();

        ArgumentCaptor<AssetRepairedEvent> eventCaptor = ArgumentCaptor.forClass(AssetRepairedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AssetRepairedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.incidentId()).isEqualTo(incidentId);
        assertThat(publishedEvent.assetId()).isEqualTo(42L);
        assertThat(publishedEvent.resolvedById()).isEqualTo(99L);
    }

    @Test
    @DisplayName("resolveIncident - failure case throws EntityNotFoundException when incident does not exist")
    void resolveIncident_notFound() {
        Long missingId = 999L;
        when(incidentReportRepository.findById(missingId)).thenReturn(Optional.empty());

        ResolveIncidentRequest request = new ResolveIncidentRequest("Fixed");

        assertThatThrownBy(() -> incidentService.resolveIncident(missingId, request, adminUser))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("Incident report not found with id: " + missingId);
    }

    @Test
    @DisplayName("getAllIncidents - returns mapped list")
    void getAllIncidents_returnsList() {
        IncidentReport report = new IncidentReport();
        report.setId(1L);
        report.setAssetId(5L);
        report.setReportedById(2L);
        report.setDescription("Issue");
        report.setSeverity(Severity.LOW);
        report.setStatus(IncidentStatus.OPEN);
        report.setCreatedAt(LocalDateTime.now(clock));

        when(incidentReportRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(report));

        List<IncidentResponse> list = incidentService.getAllIncidents(null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(1L);
    }
}

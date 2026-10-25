package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.incident.api.AssetRepairedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.CreateIncidentRequest;
import com.campus.incident.web.IncidentResponse;
import com.campus.incident.web.ResolveIncidentRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock
    private IncidentReportRepository incidentReportRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private Clock clock;
    private IncidentService incidentService;
    private final Long adminId = 99L;
    private final Long reporterId = 42L;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("UTC"));
        incidentService = new IncidentService(incidentReportRepository, eventPublisher, clock);
    }

    @Test
    void reportIncident_shouldSaveWithOpenStatusAndPublishAssetDamagedEvent() {
        CreateIncidentRequest request = new CreateIncidentRequest(10L, "Display cracked", IncidentSeverity.HIGH);

        when(incidentReportRepository.save(any(IncidentReport.class))).thenAnswer(invocation -> {
            IncidentReport report = invocation.getArgument(0);
            ReflectionTestUtils.setField(report, "id", 100L);
            return report;
        });

        IncidentResponse response = incidentService.reportIncident(request, reporterId);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(response.reportedById()).isEqualTo(reporterId);
        assertThat(response.createdAt()).isEqualTo(clock.instant());

        ArgumentCaptor<AssetDamagedEvent> eventCaptor = ArgumentCaptor.forClass(AssetDamagedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AssetDamagedEvent event = eventCaptor.getValue();
        assertThat(event.incidentId()).isEqualTo(100L);
        assertThat(event.assetId()).isEqualTo(10L);
        assertThat(event.reportedById()).isEqualTo(reporterId);
        assertThat(event.occurredAt()).isEqualTo(clock.instant());
    }

    @Test
    void resolveIncident_shouldResolveAndPublishEvent_whenOpen() {
        Long incidentId = 1L;
        IncidentReport existingReport = new IncidentReport(
                10L,
                5L,
                reporterId,
                "Defect",
                IncidentSeverity.HIGH,
                null,
                clock.instant().minusSeconds(3600));
        ReflectionTestUtils.setField(existingReport, "id", incidentId);

        when(incidentReportRepository.findById(incidentId)).thenReturn(Optional.of(existingReport));
        when(incidentReportRepository.save(any(IncidentReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResolveIncidentRequest request = new ResolveIncidentRequest("Replaced display panel");

        IncidentResponse response = incidentService.resolveIncident(incidentId, request, adminId);

        assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(response.resolvedById()).isEqualTo(adminId);
        assertThat(response.resolvedAt()).isEqualTo(clock.instant());
        assertThat(response.resolutionNotes()).isEqualTo("Replaced display panel");

        ArgumentCaptor<AssetRepairedEvent> eventCaptor = ArgumentCaptor.forClass(AssetRepairedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AssetRepairedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.incidentId()).isEqualTo(incidentId);
        assertThat(publishedEvent.assetId()).isEqualTo(10L);
        assertThat(publishedEvent.resolvedById()).isEqualTo(adminId);
    }

    @Test
    void resolveIncident_shouldThrowInvalidStateTransitionException_whenRejected() {
        Long incidentId = 2L;
        IncidentReport rejectedReport =
                new IncidentReport(10L, 5L, reporterId, "Not an issue", IncidentSeverity.LOW, null, clock.instant());
        ReflectionTestUtils.setField(rejectedReport, "id", incidentId);
        ReflectionTestUtils.setField(rejectedReport, "status", IncidentStatus.REJECTED);

        when(incidentReportRepository.findById(incidentId)).thenReturn(Optional.of(rejectedReport));

        ResolveIncidentRequest request = new ResolveIncidentRequest("Try to resolve");

        assertThatThrownBy(() -> incidentService.resolveIncident(incidentId, request, adminId))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Incident cannot be resolved from status: REJECTED");
    }

    @Test
    void resolveIncident_shouldThrowNotFoundException_whenMissing() {
        Long missingId = 404L;
        when(incidentReportRepository.findById(missingId)).thenReturn(Optional.empty());

        ResolveIncidentRequest request = new ResolveIncidentRequest("Fixed");

        assertThatThrownBy(() -> incidentService.resolveIncident(missingId, request, adminId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Incident report not found with id: " + missingId);
    }

    @Test
    void getAllIncidents_shouldFilterByStatusAndReturnPageResponse() {
        IncidentReport report = new IncidentReport(
                20L, null, reporterId, "Keyboard issue", IncidentSeverity.LOW, null, clock.instant());
        ReflectionTestUtils.setField(report, "id", 5L);

        Pageable pageable = PageRequest.of(0, 20);
        Page<IncidentReport> page = new PageImpl<>(List.of(report), pageable, 1);

        when(incidentReportRepository.findByStatus(IncidentStatus.OPEN, pageable))
                .thenReturn(page);

        PageResponse<IncidentResponse> results = incidentService.getAllIncidents(IncidentStatus.OPEN, pageable);

        assertThat(results.content()).hasSize(1);
        assertThat(results.content().get(0).id()).isEqualTo(5L);
        assertThat(results.content().get(0).status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(results.totalElements()).isEqualTo(1);
    }
}

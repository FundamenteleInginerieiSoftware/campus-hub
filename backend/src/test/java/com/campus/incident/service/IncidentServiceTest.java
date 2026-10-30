package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.CreateIncidentRequest;
import com.campus.incident.web.IncidentResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final List<IncidentStatus> ACTIVE = List.of(IncidentStatus.OPEN, IncidentStatus.IN_REPAIR);

    @Mock
    private IncidentReportRepository incidentReportRepository;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private Clock clock;
    private IncidentService incidentService;
    private final Long adminId = 99L;
    private final Long reporterId = 42L;

    @BeforeEach
    void setUp() {
        // 123 ms on purpose: the service must cut the time to whole seconds.
        clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00.123Z"), ZoneId.of("UTC"));
        incidentService = new IncidentService(incidentReportRepository, catalogApi, eventPublisher, clock);
    }

    private LocalDateTime nowInSeconds() {
        return LocalDateTime.of(2026, 10, 25, 12, 0, 0);
    }

    private IncidentReport incident(Long id, IncidentStatus status) {
        IncidentReport report = new IncidentReport(
                10L,
                null,
                reporterId,
                "Defect",
                IncidentSeverity.HIGH,
                null,
                nowInSeconds().minusHours(1));
        ReflectionTestUtils.setField(report, "id", id);
        ReflectionTestUtils.setField(report, "status", status);
        return report;
    }

    @Test
    void reportIncident_shouldSaveMarkAssetUnderMaintenanceAndPublishEvent() {
        CreateIncidentRequest request = new CreateIncidentRequest(10L, "Display cracked", IncidentSeverity.HIGH);
        when(catalogApi.getAsset(10L))
                .thenReturn(new AssetSummary(10L, "Oscilloscope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(incidentReportRepository.save(any(IncidentReport.class))).thenAnswer(invocation -> {
            IncidentReport report = invocation.getArgument(0);
            ReflectionTestUtils.setField(report, "id", 100L);
            return report;
        });

        IncidentResponse response = incidentService.reportIncident(request, reporterId);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(response.reportedById()).isEqualTo(reporterId);
        assertThat(response.createdAt()).isEqualTo(nowInSeconds());
        verify(catalogApi).markUnderMaintenance(10L);

        ArgumentCaptor<AssetDamagedEvent> eventCaptor = ArgumentCaptor.forClass(AssetDamagedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AssetDamagedEvent event = eventCaptor.getValue();
        assertThat(event.incidentId()).isEqualTo(100L);
        assertThat(event.assetId()).isEqualTo(10L);
        assertThat(event.reportedById()).isEqualTo(reporterId);
        assertThat(event.occurredAt()).isEqualTo(clock.instant());
    }

    @Test
    void reportIncident_shouldFailAndSaveNothing_whenAssetDoesNotExist() {
        CreateIncidentRequest request = new CreateIncidentRequest(999L, "Broken", IncidentSeverity.LOW);
        when(catalogApi.getAsset(999L)).thenThrow(new NotFoundException("ASSET_NOT_FOUND", "Asset 999 was not found"));

        assertThatThrownBy(() -> incidentService.reportIncident(request, reporterId))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("ASSET_NOT_FOUND");

        verify(incidentReportRepository, never()).save(any());
        verify(catalogApi, never()).markUnderMaintenance(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resolveIncident_shouldResolveAndMarkAssetAvailable_whenNoOtherActiveIncident() {
        IncidentReport report = incident(1L, IncidentStatus.OPEN);
        when(incidentReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(incidentReportRepository.save(any(IncidentReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(incidentReportRepository.findByAssetIdAndStatusIn(10L, ACTIVE)).thenReturn(List.of());

        IncidentResponse response = incidentService.resolveIncident(1L, "Replaced display panel", adminId);

        assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(response.resolvedById()).isEqualTo(adminId);
        assertThat(response.resolvedAt()).isEqualTo(nowInSeconds());
        assertThat(response.resolutionNotes()).isEqualTo("Replaced display panel");
        verify(catalogApi).markAvailable(10L);
    }

    @Test
    void resolveIncident_shouldKeepAssetUnderMaintenance_whenAnotherIncidentIsStillOpen() {
        IncidentReport report = incident(1L, IncidentStatus.OPEN);
        when(incidentReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(incidentReportRepository.save(any(IncidentReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(incidentReportRepository.findByAssetIdAndStatusIn(10L, ACTIVE))
                .thenReturn(List.of(incident(2L, IncidentStatus.OPEN)));

        incidentService.resolveIncident(1L, null, adminId);

        verify(catalogApi, never()).markAvailable(any());
    }

    @Test
    void resolveIncident_shouldWorkFromInRepair() {
        IncidentReport report = incident(1L, IncidentStatus.IN_REPAIR);
        when(incidentReportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(incidentReportRepository.save(any(IncidentReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(incidentReportRepository.findByAssetIdAndStatusIn(10L, ACTIVE)).thenReturn(List.of());

        IncidentResponse response = incidentService.resolveIncident(1L, null, adminId);

        assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(response.resolutionNotes()).isNull();
    }

    @Test
    void resolveIncident_shouldReturnUnchanged_whenAlreadyResolved() {
        IncidentReport report = incident(1L, IncidentStatus.RESOLVED);
        when(incidentReportRepository.findById(1L)).thenReturn(Optional.of(report));

        IncidentResponse response = incidentService.resolveIncident(1L, "Again", adminId);

        assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
        verify(incidentReportRepository, never()).save(any());
        verifyNoInteractions(catalogApi);
    }

    @Test
    void resolveIncident_shouldThrowInvalidStateTransitionException_whenRejected() {
        IncidentReport report = incident(2L, IncidentStatus.REJECTED);
        when(incidentReportRepository.findById(2L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> incidentService.resolveIncident(2L, "Try to resolve", adminId))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Incident cannot be resolved from status: REJECTED")
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
        verifyNoInteractions(catalogApi);
    }

    @Test
    void resolveIncident_shouldThrowNotFoundException_whenMissing() {
        when(incidentReportRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.resolveIncident(404L, "Fixed", adminId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Incident report not found with id: 404")
                .extracting("code")
                .isEqualTo("INCIDENT_NOT_FOUND");
    }

    @Test
    void getAllIncidents_shouldFilterByStatusAndReturnPageResponse() {
        IncidentReport report = incident(5L, IncidentStatus.OPEN);
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

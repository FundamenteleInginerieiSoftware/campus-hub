package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.InvalidParameterException;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final List<IncidentStatus> ACTIVE = List.of(IncidentStatus.OPEN, IncidentStatus.IN_REPAIR);
    private static final Long ASSET_ID = 10L;

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
                ASSET_ID,
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

    private static AssetSummary asset(AssetStatus status) {
        return new AssetSummary(ASSET_ID, "Oscilloscope", "DS1054Z", 1L, 1L, status);
    }

    private void givenIncident(IncidentReport report) {
        when(incidentReportRepository.findById(report.getId())).thenReturn(Optional.of(report));
    }

    private void givenSaveReturnsItsArgument() {
        when(incidentReportRepository.save(any(IncidentReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---- report ------------------------------------------------------------------------------

    @Nested
    class ReportIncident {

        private final CreateIncidentRequest request =
                new CreateIncidentRequest(ASSET_ID, "Display cracked", IncidentSeverity.HIGH);

        @Test
        void savesTheReportAsOpen_andPublishesTheEvent() {
            when(incidentReportRepository.save(any(IncidentReport.class))).thenAnswer(invocation -> {
                IncidentReport report = invocation.getArgument(0);
                ReflectionTestUtils.setField(report, "id", 100L);
                return report;
            });

            IncidentResponse response = incidentService.reportIncident(request, reporterId);

            assertThat(response.id()).isEqualTo(100L);
            assertThat(response.status()).isEqualTo(IncidentStatus.OPEN);
            assertThat(response.reportedById()).isEqualTo(reporterId);
            assertThat(response.description()).isEqualTo("Display cracked");
            assertThat(response.severity()).isEqualTo(IncidentSeverity.HIGH);
            assertThat(response.createdAt()).isEqualTo(nowInSeconds());
            assertThat(response.resolvedAt()).isNull();

            ArgumentCaptor<AssetDamagedEvent> eventCaptor = ArgumentCaptor.forClass(AssetDamagedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            AssetDamagedEvent event = eventCaptor.getValue();
            assertThat(event.incidentId()).isEqualTo(100L);
            assertThat(event.assetId()).isEqualTo(ASSET_ID);
            assertThat(event.reportedById()).isEqualTo(reporterId);
            assertThat(event.occurredAt()).isEqualTo(clock.instant());
        }

        // #164: the asset lock comes before the INSERT, so report and resolve on one asset never race.
        @Test
        void locksTheAssetBeforeSavingTheReport() {
            givenSaveReturnsItsArgument();

            incidentService.reportIncident(request, reporterId);

            InOrder order = inOrder(catalogApi, incidentReportRepository, eventPublisher);
            order.verify(catalogApi).markUnderMaintenance(ASSET_ID);
            order.verify(incidentReportRepository).save(any(IncidentReport.class));
            order.verify(eventPublisher).publishEvent(any(AssetDamagedEvent.class));
            verify(catalogApi, never()).getAsset(any());
        }

        @Test
        void savesNothing_whenTheAssetDoesNotExist() {
            doThrow(new NotFoundException("ASSET_NOT_FOUND", "Asset 10 was not found"))
                    .when(catalogApi)
                    .markUnderMaintenance(ASSET_ID);

            assertThatThrownBy(() -> incidentService.reportIncident(request, reporterId))
                    .isInstanceOf(NotFoundException.class)
                    .extracting("code")
                    .isEqualTo("ASSET_NOT_FOUND");

            verify(incidentReportRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        // #164: a retired asset used to burn an id (INSERT, then rollback). Now nothing is inserted.
        @Test
        void savesNothing_whenTheAssetIsRetired() {
            doThrow(new InvalidStateTransitionException("ASSET_RETIRED", "Asset is retired"))
                    .when(catalogApi)
                    .markUnderMaintenance(ASSET_ID);

            assertThatThrownBy(() -> incidentService.reportIncident(request, reporterId))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .extracting("code")
                    .isEqualTo("ASSET_RETIRED");

            verify(incidentReportRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    // ---- start repair ------------------------------------------------------------------------

    @Nested
    class StartRepair {

        @Test
        void movesOpenToInRepair_andLeavesTheAssetAlone() {
            givenIncident(incident(1L, IncidentStatus.OPEN));
            givenSaveReturnsItsArgument();

            IncidentResponse response = incidentService.startRepair(1L);

            assertThat(response.status()).isEqualTo(IncidentStatus.IN_REPAIR);
            assertThat(response.resolvedAt()).isNull();
            verifyNoInteractions(catalogApi);
        }

        @Test
        void returnsItUnchanged_whenAlreadyInRepair() {
            givenIncident(incident(1L, IncidentStatus.IN_REPAIR));

            IncidentResponse response = incidentService.startRepair(1L);

            assertThat(response.status()).isEqualTo(IncidentStatus.IN_REPAIR);
            verify(incidentReportRepository, never()).save(any());
        }

        @ParameterizedTest(name = "from {0}")
        @CsvSource({"RESOLVED", "REJECTED"})
        void fails_fromAClosedIncident(IncidentStatus closed) {
            givenIncident(incident(1L, closed));

            assertThatThrownBy(() -> incidentService.startRepair(1L))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("Repair cannot start from status: " + closed)
                    .extracting("code")
                    .isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
            verify(incidentReportRepository, never()).save(any());
        }

        @Test
        void fails_whenTheIncidentDoesNotExist() {
            when(incidentReportRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> incidentService.startRepair(404L))
                    .isInstanceOf(NotFoundException.class)
                    .extracting("code")
                    .isEqualTo("INCIDENT_NOT_FOUND");
        }
    }

    // ---- resolve -----------------------------------------------------------------------------

    @Nested
    class ResolveIncident {

        @ParameterizedTest(name = "from {0}")
        @CsvSource({"OPEN", "IN_REPAIR"})
        void resolves_andMarksTheAssetAvailable_whenNoOtherIncidentIsActive(IncidentStatus from) {
            givenIncident(incident(1L, from));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(false);
            when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset(AssetStatus.UNDER_MAINTENANCE));

            IncidentResponse response = incidentService.resolveIncident(1L, "Replaced display panel", adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(response.resolvedById()).isEqualTo(adminId);
            assertThat(response.resolvedAt()).isEqualTo(nowInSeconds());
            assertThat(response.resolutionNotes()).isEqualTo("Replaced display panel");
            verify(catalogApi).markAvailable(ASSET_ID);
        }

        // #108 review: lock the asset row first, then ask "any incident still active?".
        @Test
        void locksTheAssetBeforeCheckingForOtherIncidents() {
            givenIncident(incident(1L, IncidentStatus.OPEN));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(false);
            when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset(AssetStatus.UNDER_MAINTENANCE));

            incidentService.resolveIncident(1L, null, adminId);

            InOrder order = inOrder(catalogApi, incidentReportRepository);
            order.verify(incidentReportRepository).save(any(IncidentReport.class));
            order.verify(catalogApi).lockForBooking(ASSET_ID);
            order.verify(incidentReportRepository).existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE);
            order.verify(catalogApi).markAvailable(ASSET_ID);
        }

        @Test
        void keepsTheAssetUnderMaintenance_whenAnotherIncidentIsStillActive() {
            givenIncident(incident(1L, IncidentStatus.OPEN));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(true);

            IncidentResponse response = incidentService.resolveIncident(1L, null, adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
            verify(catalogApi, never()).getAsset(any());
            verify(catalogApi, never()).markAvailable(any());
        }

        // #146: the asset was retired while the incident was open. The incident must still close.
        @Test
        void resolves_andLeavesARetiredAssetRetired() {
            givenIncident(incident(1L, IncidentStatus.IN_REPAIR));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(false);
            when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset(AssetStatus.RETIRED));

            IncidentResponse response = incidentService.resolveIncident(1L, "Beyond repair", adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
            verify(catalogApi, never()).markAvailable(any());
        }

        @Test
        void doesNotTouchAnAssetThatIsAlreadyAvailable() {
            givenIncident(incident(1L, IncidentStatus.OPEN));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(false);
            when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset(AssetStatus.AVAILABLE));

            incidentService.resolveIncident(1L, null, adminId);

            verify(catalogApi, never()).markAvailable(any());
        }

        @Test
        void returnsItUnchanged_whenAlreadyResolved() {
            givenIncident(incident(1L, IncidentStatus.RESOLVED));

            IncidentResponse response = incidentService.resolveIncident(1L, "Again", adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.RESOLVED);
            verify(incidentReportRepository, never()).save(any());
            verifyNoInteractions(catalogApi);
        }

        @Test
        void fails_fromRejected() {
            givenIncident(incident(2L, IncidentStatus.REJECTED));

            assertThatThrownBy(() -> incidentService.resolveIncident(2L, "Notes", adminId))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("Incident cannot be resolved from status: REJECTED")
                    .extracting("code")
                    .isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
            verifyNoInteractions(catalogApi);
        }

        @Test
        void fails_whenTheIncidentDoesNotExist() {
            when(incidentReportRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> incidentService.resolveIncident(404L, "Notes", adminId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Incident report not found with id: 404")
                    .extracting("code")
                    .isEqualTo("INCIDENT_NOT_FOUND");
        }
    }

    // ---- reject ------------------------------------------------------------------------------

    @Nested
    class RejectIncident {

        @ParameterizedTest(name = "from {0}")
        @CsvSource({"OPEN", "IN_REPAIR"})
        void rejects_andMarksTheAssetAvailable_whenNoOtherIncidentIsActive(IncidentStatus from) {
            givenIncident(incident(1L, from));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(false);
            when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset(AssetStatus.UNDER_MAINTENANCE));

            IncidentResponse response = incidentService.rejectIncident(1L, "Works fine, wrong cable", adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.REJECTED);
            assertThat(response.resolvedById()).isEqualTo(adminId);
            assertThat(response.resolvedAt()).isEqualTo(nowInSeconds());
            assertThat(response.resolutionNotes()).isEqualTo("Works fine, wrong cable");
            verify(catalogApi).lockForBooking(ASSET_ID);
            verify(catalogApi).markAvailable(ASSET_ID);
        }

        @Test
        void keepsTheAssetUnderMaintenance_whenAnotherIncidentIsStillActive() {
            givenIncident(incident(1L, IncidentStatus.IN_REPAIR));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(true);

            IncidentResponse response = incidentService.rejectIncident(1L, null, adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.REJECTED);
            verify(catalogApi, never()).markAvailable(any());
        }

        // #146 for reject: same retired-asset rule as resolve.
        @Test
        void rejects_andLeavesARetiredAssetRetired() {
            givenIncident(incident(1L, IncidentStatus.OPEN));
            givenSaveReturnsItsArgument();
            when(incidentReportRepository.existsByAssetIdAndStatusIn(ASSET_ID, ACTIVE))
                    .thenReturn(false);
            when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset(AssetStatus.RETIRED));

            IncidentResponse response = incidentService.rejectIncident(1L, null, adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.REJECTED);
            verify(catalogApi, never()).markAvailable(any());
        }

        @Test
        void returnsItUnchanged_whenAlreadyRejected() {
            givenIncident(incident(1L, IncidentStatus.REJECTED));

            IncidentResponse response = incidentService.rejectIncident(1L, "Again", adminId);

            assertThat(response.status()).isEqualTo(IncidentStatus.REJECTED);
            verify(incidentReportRepository, never()).save(any());
            verifyNoInteractions(catalogApi);
        }

        @Test
        void fails_fromResolved() {
            givenIncident(incident(1L, IncidentStatus.RESOLVED));

            assertThatThrownBy(() -> incidentService.rejectIncident(1L, null, adminId))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("Incident cannot be rejected from status: RESOLVED")
                    .extracting("code")
                    .isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
            verifyNoInteractions(catalogApi);
        }

        @Test
        void fails_whenTheIncidentDoesNotExist() {
            when(incidentReportRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> incidentService.rejectIncident(404L, null, adminId))
                    .isInstanceOf(NotFoundException.class)
                    .extracting("code")
                    .isEqualTo("INCIDENT_NOT_FOUND");
        }
    }

    // ---- lists -------------------------------------------------------------------------------

    @Nested
    class Lists {

        @Test
        void adminList_filtersByStatus_newestFirst() {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(incidentReportRepository.findByStatus(any(), pageable.capture()))
                    .thenAnswer(invocation ->
                            new PageImpl<>(List.of(incident(5L, IncidentStatus.OPEN)), invocation.getArgument(1), 1));

            PageResponse<IncidentResponse> results = incidentService.getAllIncidents(IncidentStatus.OPEN, 0, 20);

            assertThat(results.content()).extracting(IncidentResponse::id).containsExactly(5L);
            assertThat(results.totalElements()).isEqualTo(1);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            // createdAt has whole seconds only, so the id breaks ties (#108 review).
            assertThat(pageable.getValue().getSort())
                    .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            verify(incidentReportRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        void adminList_withoutStatus_returnsEverything() {
            Page<IncidentReport> page =
                    new PageImpl<>(List.of(incident(6L, IncidentStatus.RESOLVED), incident(5L, IncidentStatus.OPEN)));
            when(incidentReportRepository.findAll(any(Pageable.class))).thenReturn(page);

            PageResponse<IncidentResponse> results = incidentService.getAllIncidents(null, 0, 20);

            assertThat(results.content()).extracting(IncidentResponse::id).containsExactly(6L, 5L);
            verify(incidentReportRepository, never()).findByStatus(any(), any());
        }

        @Test
        void myIncidents_asksOnlyForTheReportersOwn() {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(incidentReportRepository.findByReportedById(eq(reporterId), pageable.capture()))
                    .thenAnswer(invocation ->
                            new PageImpl<>(List.of(incident(7L, IncidentStatus.OPEN)), invocation.getArgument(1), 1));

            PageResponse<IncidentResponse> results = incidentService.getMyIncidents(reporterId, 2, 5);

            assertThat(results.content())
                    .extracting(IncidentResponse::reportedById)
                    .containsExactly(reporterId);
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }

        // #146, #164: bad page/size is a 400, never a 500 from Hibernate.
        @ParameterizedTest(name = "page={0}, size={1}")
        @CsvSource({
            "-1, 20, Page index must not be negative",
            "0, 0, Page size must be between 1 and 100",
            "0, 101, Page size must be between 1 and 100",
            "999999999, 100, Page offset exceeds maximum supported value"
        })
        void badPageOrSize_isA400(int page, int size, String message) {
            assertThatThrownBy(() -> incidentService.getMyIncidents(reporterId, page, size))
                    .isInstanceOf(InvalidParameterException.class)
                    .hasMessage(message)
                    .extracting("code")
                    .isEqualTo("INVALID_PARAMETER");
            assertThatThrownBy(() -> incidentService.getAllIncidents(null, page, size))
                    .isInstanceOf(InvalidParameterException.class);
            verifyNoInteractions(incidentReportRepository);
        }

        @Test
        void theLargestAllowedPageAndSizeAreAccepted() {
            Pageable pageable = IncidentService.pageRequest(Integer.MAX_VALUE / 100, 100);

            assertThat(pageable.getOffset()).isLessThanOrEqualTo(Integer.MAX_VALUE);
        }
    }

    // ---- one incident ------------------------------------------------------------------------

    @Nested
    class GetIncident {

        @Test
        void isVisibleToItsReporter() {
            givenIncident(incident(1L, IncidentStatus.OPEN));

            assertThat(incidentService.getIncident(1L, reporterId, false).id()).isEqualTo(1L);
        }

        @Test
        void isVisibleToAnAdmin() {
            givenIncident(incident(1L, IncidentStatus.OPEN));

            assertThat(incidentService.getIncident(1L, adminId, true).id()).isEqualTo(1L);
        }

        // Same answer as a missing id, so another student cannot learn which ids exist.
        @Test
        void isA404_forAnotherStudent() {
            givenIncident(incident(1L, IncidentStatus.OPEN));

            assertThatThrownBy(() -> incidentService.getIncident(1L, 7L, false))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Incident report not found with id: 1")
                    .extracting("code")
                    .isEqualTo("INCIDENT_NOT_FOUND");
        }

        @Test
        void isA404_whenItDoesNotExist() {
            when(incidentReportRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> incidentService.getIncident(404L, adminId, true))
                    .isInstanceOf(NotFoundException.class)
                    .extracting("code")
                    .isEqualTo("INCIDENT_NOT_FOUND");
        }
    }
}

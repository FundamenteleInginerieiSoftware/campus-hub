package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.InvalidParameterException;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reassignment.service.optimal.OptimalReassignmentSolver;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class ReassignmentServiceTest {

    private static final Long DAMAGED_ASSET_ID = 10L;
    private static final Long SUBSTITUTE_ASSET_ID = 20L;
    private static final Long INCIDENT_ID = 100L;
    private static final Long USER_ID = 42L;

    // Fixed clock with nanos: 2026-11-02T08:00:00.123456789Z -> In Europe/Bucharest (UTC+2 in winter) this is
    // 2026-11-02T10:00:00.123456789 (Monday)
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-11-02T08:00:00.123456789Z"), ZoneId.of("Europe/Bucharest"));
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 11, 2, 10, 0, 0);

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ReassignmentLogRepository reassignmentLogRepository;

    @Mock
    private SubstituteStrategy primaryStrategy;

    @Mock
    private SubstituteStrategy secondaryStrategy;

    @Mock
    private OptimalReassignmentSolver optimalReassignmentSolver;

    private ReassignmentService reassignmentService;

    private final AssetDamagedEvent event = new AssetDamagedEvent(INCIDENT_ID, DAMAGED_ASSET_ID, 999L, Instant.now());
    private final AssetSummary damagedAsset =
            new AssetSummary(DAMAGED_ASSET_ID, "Damaged Scope", "DS1054Z", 1L, 100L, AssetStatus.UNDER_MAINTENANCE);
    private final AssetSummary substituteAsset =
            new AssetSummary(SUBSTITUTE_ASSET_ID, "Substitute Scope", "DS1054Z", 1L, 100L, AssetStatus.AVAILABLE);

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ReassignmentBookingProcessor bookingProcessor = new ReassignmentBookingProcessor(
                reservationApi,
                reassignmentLogRepository,
                List.of(primaryStrategy, secondaryStrategy),
                clock,
                transactionManager);
        reassignmentService = new ReassignmentService(
                catalogApi,
                reservationApi,
                notificationService,
                bookingProcessor,
                reassignmentLogRepository,
                optimalReassignmentSolver,
                clock);
    }

    @Test
    @DisplayName("Reassigns booking when strategy finds a free substitute and notifies student")
    void reassignsBookingSuccessfully() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), eq(FIXED_NOW)))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of(substituteAsset));
        when(primaryStrategy.name()).thenReturn("SameModelStrategy");
        when(reservationApi.moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID))
                .thenReturn(true);

        reassignmentService.handle(event);

        verify(reservationApi).moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);
        ArgumentCaptor<ReassignmentLog> captor = ArgumentCaptor.forClass(ReassignmentLog.class);
        verify(reassignmentLogRepository).save(captor.capture());
        ReassignmentLog savedLog = captor.getValue();
        assertThat(savedLog.getIncidentId()).isEqualTo(INCIDENT_ID);
        assertThat(savedLog.getReservationId()).isEqualTo(1L);
        assertThat(savedLog.getFromAssetId()).isEqualTo(DAMAGED_ASSET_ID);
        assertThat(savedLog.getToAssetId()).isEqualTo(SUBSTITUTE_ASSET_ID);
        assertThat(savedLog.getOutcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(savedLog.getStrategy()).isEqualTo("SameModelStrategy");
        assertThat(savedLog.getCreatedAt().getNano()).isZero();
        assertThat(savedLog.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 10, 0, 0));
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Reassigned"), contains("Substitute Scope"));
    }

    @Test
    @DisplayName("Notification failure does not throw or prevent reassignment completion")
    void notificationFailureDoesNotRollbackReassignment() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), eq(FIXED_NOW)))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of(substituteAsset));
        when(primaryStrategy.name()).thenReturn("SameModelStrategy");
        when(reservationApi.moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID))
                .thenReturn(true);
        doThrow(new RuntimeException("SES service down"))
                .when(notificationService)
                .notifyUser(any(), any(), any());

        reassignmentService.handle(event);

        verify(reservationApi).moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);
        verify(reassignmentLogRepository).save(any());
    }

    @Test
    @DisplayName("Falls back to next strategy when candidate is no longer free during move")
    void fallsBackToNextStrategyWhenSubstituteNoLongerFree() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);
        AssetSummary fallbackSubstitute =
                new AssetSummary(30L, "Category Substitute", "DS2000", 1L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), eq(FIXED_NOW)))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of(substituteAsset));
        doThrow(new SubstituteNoLongerFreeException("No longer free"))
                .when(reservationApi)
                .moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);

        when(secondaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of(fallbackSubstitute));
        when(secondaryStrategy.name()).thenReturn("SameCategoryStrategy");
        when(reservationApi.moveToAsset(1L, 30L, "Incident #" + INCIDENT_ID)).thenReturn(true);

        reassignmentService.handle(event);

        verify(reservationApi).moveToAsset(1L, 30L, "Incident #" + INCIDENT_ID);
        verify(reassignmentLogRepository)
                .save(argThat(log -> log.getToAssetId().equals(30L)
                        && log.getOutcome() == ReassignmentOutcome.REASSIGNED
                        && log.getStrategy().equals("SameCategoryStrategy")));
    }

    @Test
    @DisplayName("Tries remaining candidates of the same strategy before falling back to next strategy (#149)")
    void triesRemainingCandidatesOfSameStrategyBeforeFallingBack() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);
        AssetSummary secondSameModelSubstitute =
                new AssetSummary(25L, "Second Same-Model Scope", "DS1054Z", 1L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findCandidates(booking, damagedAsset))
                .thenReturn(List.of(substituteAsset, secondSameModelSubstitute));
        when(primaryStrategy.name()).thenReturn("SameModelStrategy");

        doThrow(new SubstituteNoLongerFreeException("Candidate 20 raced"))
                .when(reservationApi)
                .moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);
        when(reservationApi.moveToAsset(1L, 25L, "Incident #" + INCIDENT_ID)).thenReturn(true);

        reassignmentService.handle(event);

        verify(reservationApi).moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);
        verify(reservationApi).moveToAsset(1L, 25L, "Incident #" + INCIDENT_ID);
        verify(secondaryStrategy, never()).findCandidates(any(), any());
        verify(reassignmentLogRepository)
                .save(argThat(log -> log.getToAssetId().equals(25L)
                        && log.getOutcome() == ReassignmentOutcome.REASSIGNED
                        && log.getStrategy().equals("SameModelStrategy")));
        verify(notificationService)
                .notifyUser(eq(USER_ID), eq("Reservation Reassigned"), contains("Second Same-Model Scope"));
    }

    @Test
    @DisplayName(
            "Candidate that threw in primary strategy is not retried if present in secondary strategy candidates (#149)")
    void candidateAlreadyAttemptedInPrimaryStrategyIsSkippedInSecondaryStrategy() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);
        AssetSummary fallbackSubstitute =
                new AssetSummary(30L, "Category Substitute", "DS2000", 1L, 100L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of(substituteAsset));
        doThrow(new SubstituteNoLongerFreeException("Asset 20 busy"))
                .when(reservationApi)
                .moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);

        when(secondaryStrategy.findCandidates(booking, damagedAsset))
                .thenReturn(List.of(substituteAsset, fallbackSubstitute));
        when(secondaryStrategy.name()).thenReturn("SameCategoryStrategy");
        when(reservationApi.moveToAsset(1L, 30L, "Incident #" + INCIDENT_ID)).thenReturn(true);

        reassignmentService.handle(event);

        // Candidate 20 was tried only once (in primary strategy) and skipped in secondary strategy
        verify(reservationApi, times(1)).moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);
        verify(reservationApi).moveToAsset(1L, 30L, "Incident #" + INCIDENT_ID);
        verify(reassignmentLogRepository)
                .save(argThat(log -> log.getToAssetId().equals(30L)
                        && log.getOutcome() == ReassignmentOutcome.REASSIGNED
                        && log.getStrategy().equals("SameCategoryStrategy")));
    }

    @Test
    @DisplayName("Force cancels booking when no strategy finds a free substitute")
    void forceCancelsWhenNoSubstituteFound() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), eq(FIXED_NOW)))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of());
        when(secondaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of());
        when(reservationApi.forceCancel(1L, "Equipment failure, incident #" + INCIDENT_ID))
                .thenReturn(true);

        reassignmentService.handle(event);

        verify(reservationApi).forceCancel(1L, "Equipment failure, incident #" + INCIDENT_ID);
        verify(reassignmentLogRepository)
                .save(argThat(log -> log.getIncidentId().equals(INCIDENT_ID)
                        && log.getReservationId().equals(1L)
                        && log.getFromAssetId().equals(DAMAGED_ASSET_ID)
                        && log.getToAssetId() == null
                        && log.getOutcome() == ReassignmentOutcome.FORCE_CANCELLED
                        && log.getStrategy() == null));
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Cancelled"), contains("cancelled"));
    }

    @Test
    @DisplayName("Processes multiple upcoming bookings in chronological start-time order")
    void processesBookingsInStartTimeOrder() {
        LocalDateTime earlierStart = LocalDateTime.of(2026, 11, 2, 10, 0);
        LocalDateTime laterStart = LocalDateTime.of(2026, 11, 2, 14, 0);

        ReservationSummary earlierBooking = new ReservationSummary(
                2L, USER_ID, DAMAGED_ASSET_ID, earlierStart, earlierStart.plusHours(2), ReservationStatus.CONFIRMED);
        ReservationSummary laterBooking = new ReservationSummary(
                1L, USER_ID, DAMAGED_ASSET_ID, laterStart, laterStart.plusHours(2), ReservationStatus.CONFIRMED);

        // List returned in reverse order
        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), eq(FIXED_NOW)))
                .thenReturn(List.of(laterBooking, earlierBooking));

        when(primaryStrategy.findCandidates(any(), any())).thenReturn(List.of());
        when(secondaryStrategy.findCandidates(any(), any())).thenReturn(List.of());

        reassignmentService.handle(event);

        InOrder inOrder = inOrder(reservationApi);
        inOrder.verify(reservationApi).forceCancel(eq(2L), anyString()); // Earlier booking first!
        inOrder.verify(reservationApi).forceCancel(eq(1L), anyString()); // Later booking second!
    }

    @Test
    @DisplayName("Does not write log or send notification when student cancelled between query and move/forceCancel")
    void skipsLogAndNotificationWhenStudentCancelledInBetween() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), eq(FIXED_NOW)))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findCandidates(booking, damagedAsset)).thenReturn(List.of(substituteAsset));
        when(reservationApi.moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID))
                .thenReturn(false);

        reassignmentService.handle(event);

        verify(reassignmentLogRepository, never()).save(any());
        verify(notificationService, never()).notifyUser(any(), any(), any());
    }

    @Test
    @DisplayName("Exception during single booking reassignment skips that booking and continues processing others")
    void exceptionOnOneBookingContinuesProcessingRemainingBookings() {
        LocalDateTime start1 = LocalDateTime.of(2026, 11, 2, 12, 0);
        LocalDateTime start2 = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime start3 = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary b1 = new ReservationSummary(
                1L, 10L, DAMAGED_ASSET_ID, start1, start1.plusHours(2), ReservationStatus.CONFIRMED);
        ReservationSummary b2 = new ReservationSummary(
                2L, 20L, DAMAGED_ASSET_ID, start2, start2.plusHours(2), ReservationStatus.CONFIRMED);
        ReservationSummary b3 = new ReservationSummary(
                3L, 30L, DAMAGED_ASSET_ID, start3, start3.plusHours(2), ReservationStatus.CONFIRMED);

        ReassignmentBookingProcessor mockProcessor = mock(ReassignmentBookingProcessor.class);
        ReassignmentService service = new ReassignmentService(
                catalogApi,
                reservationApi,
                notificationService,
                mockProcessor,
                reassignmentLogRepository,
                optimalReassignmentSolver,
                clock);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), eq(FIXED_NOW)))
                .thenReturn(List.of(b1, b2, b3));

        when(mockProcessor.processSingleBooking(eq(event), eq(b1), eq(damagedAsset)))
                .thenReturn(Optional.of(new ReassignmentBookingProcessor.ReassignmentExecutionResult(
                        ReassignmentOutcome.REASSIGNED, "Sub 1")));
        when(mockProcessor.processSingleBooking(eq(event), eq(b2), eq(damagedAsset)))
                .thenThrow(new ObjectOptimisticLockingFailureException("Stale version on b2", new Throwable()));
        when(mockProcessor.processSingleBooking(eq(event), eq(b3), eq(damagedAsset)))
                .thenReturn(Optional.of(new ReassignmentBookingProcessor.ReassignmentExecutionResult(
                        ReassignmentOutcome.REASSIGNED, "Sub 3")));

        service.handle(event);

        verify(mockProcessor).processSingleBooking(eq(event), eq(b1), eq(damagedAsset));
        verify(mockProcessor).processSingleBooking(eq(event), eq(b2), eq(damagedAsset));
        verify(mockProcessor).processSingleBooking(eq(event), eq(b3), eq(damagedAsset));

        verify(notificationService).notifyUser(eq(10L), eq("Reservation Reassigned"), contains("Sub 1"));
        verify(notificationService).notifyUser(eq(30L), eq("Reservation Reassigned"), contains("Sub 3"));
        verify(notificationService, never()).notifyUser(eq(20L), any(), any());
    }

    @Test
    @DisplayName("getLogs returns paginated response mapped from repository")
    void getLogsReturnsPaginatedResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 2, 10, 0);
        ReassignmentLog logEntry = new ReassignmentLog(
                INCIDENT_ID,
                1L,
                DAMAGED_ASSET_ID,
                SUBSTITUTE_ASSET_ID,
                ReassignmentOutcome.REASSIGNED,
                "SameModelStrategy",
                now);

        org.springframework.data.domain.Page<ReassignmentLog> page = new org.springframework.data.domain.PageImpl<>(
                List.of(logEntry), org.springframework.data.domain.PageRequest.of(0, 10), 1);

        when(reassignmentLogRepository.findAllByOrderByCreatedAtDesc(
                        any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        com.campus.common.paging.PageResponse<com.campus.reassignment.web.ReassignmentLogResponse> response =
                reassignmentService.getLogs(0, 10);

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);

        com.campus.reassignment.web.ReassignmentLogResponse item =
                response.content().get(0);
        assertThat(item.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(item.reservationId()).isEqualTo(1L);
        assertThat(item.fromAssetId()).isEqualTo(DAMAGED_ASSET_ID);
        assertThat(item.toAssetId()).isEqualTo(SUBSTITUTE_ASSET_ID);
        assertThat(item.outcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(item.strategy()).isEqualTo("SameModelStrategy");
        assertThat(item.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("getLogs rejects huge page offset overflow (#150)")
    void getLogsRejectsHugePageOffsetOverflow() {
        assertThatThrownBy(() -> reassignmentService.getLogs(999_999_999, 100))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page offset exceeds maximum supported value");
    }

    @Test
    @DisplayName("getLogs validates boundary conditions for page and size")
    void getLogsBoundaryValidation() {
        // Negative page
        assertThatThrownBy(() -> reassignmentService.getLogs(-1, 10))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page index must not be negative");

        // Size < 1 (0)
        assertThatThrownBy(() -> reassignmentService.getLogs(0, 0))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        // Size > 100 (101)
        assertThatThrownBy(() -> reassignmentService.getLogs(0, 101))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        // Overflow: (long) page * size > Integer.MAX_VALUE
        long half = ((long) Integer.MAX_VALUE / 2) + 1;
        assertThatThrownBy(() -> reassignmentService.getLogs((int) half, 2))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page offset exceeds maximum supported value");

        // Valid boundaries: size = 1, size = 100, page * size == Integer.MAX_VALUE
        when(reassignmentLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThatCode(() -> reassignmentService.getLogs(0, 1)).doesNotThrowAnyException();
        assertThatCode(() -> reassignmentService.getLogs(0, 100)).doesNotThrowAnyException();
        assertThatCode(() -> reassignmentService.getLogs(Integer.MAX_VALUE, 1)).doesNotThrowAnyException();
    }
}

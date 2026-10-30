package com.campus.reassignment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReassignmentServiceTest {

    private static final Long DAMAGED_ASSET_ID = 10L;
    private static final Long SUBSTITUTE_ASSET_ID = 20L;
    private static final Long INCIDENT_ID = 100L;
    private static final Long USER_ID = 42L;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

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

    private ReassignmentService reassignmentService;

    private final AssetDamagedEvent event = new AssetDamagedEvent(INCIDENT_ID, DAMAGED_ASSET_ID, 999L, Instant.now());
    private final AssetSummary damagedAsset =
            new AssetSummary(DAMAGED_ASSET_ID, "Damaged Scope", "DS1054Z", 1L, 100L, AssetStatus.UNDER_MAINTENANCE);
    private final AssetSummary substituteAsset =
            new AssetSummary(SUBSTITUTE_ASSET_ID, "Substitute Scope", "DS1054Z", 1L, 100L, AssetStatus.AVAILABLE);

    @BeforeEach
    void setUp() {
        reassignmentService = new ReassignmentService(
                catalogApi,
                reservationApi,
                notificationService,
                reassignmentLogRepository,
                List.of(primaryStrategy, secondaryStrategy),
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
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findSubstitute(booking, damagedAsset)).thenReturn(Optional.of(substituteAsset));
        when(primaryStrategy.name()).thenReturn("SameModelStrategy");
        when(reservationApi.moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID))
                .thenReturn(true);

        reassignmentService.handle(event);

        verify(reservationApi).moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);
        verify(reassignmentLogRepository)
                .save(argThat(log -> log.getIncidentId().equals(INCIDENT_ID)
                        && log.getReservationId().equals(1L)
                        && log.getFromAssetId().equals(DAMAGED_ASSET_ID)
                        && log.getToAssetId().equals(SUBSTITUTE_ASSET_ID)
                        && log.getOutcome() == ReassignmentOutcome.REASSIGNED
                        && log.getStrategy().equals("SameModelStrategy")));
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
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findSubstitute(booking, damagedAsset)).thenReturn(Optional.of(substituteAsset));
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
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findSubstitute(booking, damagedAsset)).thenReturn(Optional.of(substituteAsset));
        doThrow(new SubstituteNoLongerFreeException("No longer free"))
                .when(reservationApi)
                .moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID);

        when(secondaryStrategy.findSubstitute(booking, damagedAsset)).thenReturn(Optional.of(fallbackSubstitute));
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
    @DisplayName("Force cancels booking when no strategy finds a free substitute")
    void forceCancelsWhenNoSubstituteFound() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        ReservationSummary booking =
                new ReservationSummary(1L, USER_ID, DAMAGED_ASSET_ID, start, end, ReservationStatus.CONFIRMED);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damagedAsset);
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findSubstitute(booking, damagedAsset)).thenReturn(Optional.empty());
        when(secondaryStrategy.findSubstitute(booking, damagedAsset)).thenReturn(Optional.empty());
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
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(laterBooking, earlierBooking));

        when(primaryStrategy.findSubstitute(any(), any())).thenReturn(Optional.empty());
        when(secondaryStrategy.findSubstitute(any(), any())).thenReturn(Optional.empty());

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
        when(reservationApi.findUpcomingActiveByAsset(eq(DAMAGED_ASSET_ID), any()))
                .thenReturn(List.of(booking));
        when(primaryStrategy.findSubstitute(booking, damagedAsset)).thenReturn(Optional.of(substituteAsset));
        when(reservationApi.moveToAsset(1L, SUBSTITUTE_ASSET_ID, "Incident #" + INCIDENT_ID))
                .thenReturn(false);

        reassignmentService.handle(event);

        verify(reassignmentLogRepository, never()).save(any());
        verify(notificationService, never()).notifyUser(any(), any(), any());
    }
}

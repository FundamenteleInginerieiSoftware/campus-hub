package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.LifecycleSweepSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * What the lifecycle sweep does when one booking goes wrong: the sweep must go on with the
 * others, count only what really changed, and never fail because an e-mail failed.
 */
@ExtendWith(MockitoExtension.class)
class ReservationLifecycleFailureTest {

    private static final Long ASSET_ID = 10L;

    // Monday 2 Nov 2026, 10:00 in Bucharest.
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationService reservationService;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private NotificationService notificationService;

    private ReservationLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        lifecycleService = new ReservationLifecycleService(
                reservationRepository, reservationService, catalogApi, notificationService, clock);
    }

    private Reservation reservation(long id, long userId, int startHour, int endHour) {
        Reservation r = new Reservation(
                userId,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, startHour, 0),
                LocalDateTime.of(2026, 11, 2, endHour, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    @Test
    void noShowThatChangedNothingIsNotCountedNorNotified() {
        Reservation unclaimed = reservation(1L, 101L, 9, 11);
        when(reservationRepository.findConfirmedBeforeCutoff(any())).thenReturn(List.of(unclaimed));
        when(reservationService.markNoShow(1L)).thenReturn(false);

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.noShowReleasedCount()).isZero();
        verify(notificationService, never()).notifyUser(anyLong(), any(), any());
    }

    @Test
    void aConcurrentFailureOnOneOverdueBookingDoesNotStopTheSweep() {
        Reservation failing = reservation(2L, 102L, 8, 9);
        Reservation unchanged = reservation(3L, 103L, 8, 9);
        Reservation marked = reservation(4L, 104L, 8, 9);
        when(reservationRepository.findCheckedOutPastEnd(any())).thenReturn(List.of(failing, unchanged, marked));
        when(reservationService.markOverdue(2L)).thenThrow(new RuntimeException("optimistic lock"));
        when(reservationService.markOverdue(3L)).thenReturn(false);
        when(reservationService.markOverdue(4L)).thenReturn(true);

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.markedOverdueCount()).isEqualTo(1);
        verify(notificationService).notifyUser(eq(104L), eq("Equipment Overdue"), any());
        verify(notificationService, never()).notifyUser(eq(102L), any(), any());
        verify(notificationService, never()).notifyUser(eq(103L), any(), any());
    }

    // Common setup: asset 10 is held by an OVERDUE booking and one booking starting in 30 minutes is threatened.
    private Reservation threatenedBookingWithTwoSubstitutes() {
        Reservation overdue = reservation(5L, 105L, 8, 9);
        Reservation threatened = reservation(6L, 106L, 10, 12);
        // Asset 10, starts at 10:00 (now), so it is inside the 60-minute cascade horizon.
        when(reservationRepository.findByStatus(ReservationStatus.OVERDUE)).thenReturn(List.of(overdue));
        when(reservationRepository.findUpcomingConfirmedByAsset(eq(ASSET_ID), any()))
                .thenReturn(List.of(threatened));
        when(catalogApi.getAsset(ASSET_ID))
                .thenReturn(new AssetSummary(ASSET_ID, "Scope 1", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
        when(catalogApi.findAvailableByModel("DS1054Z", ASSET_ID))
                .thenReturn(List.of(
                        new AssetSummary(20L, "Scope 2", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE),
                        new AssetSummary(21L, "Scope 3", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE)));
        when(reservationService.isAssetFree(anyLong(), any(), any(), anyLong())).thenReturn(true);
        return threatened;
    }

    @Test
    void preventiveMoveTriesTheNextSubstituteWhenTheFirstWasTakenMeanwhile() {
        threatenedBookingWithTwoSubstitutes();
        when(reservationService.moveToAsset(eq(6L), eq(20L), any()))
                .thenThrow(new SubstituteNoLongerFreeException("taken"));
        when(reservationService.moveToAsset(eq(6L), eq(21L), any())).thenReturn(true);

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.preventivelyReassignedReservationIds()).containsExactly(6L);
        verify(notificationService).notifyUser(eq(106L), eq("Proactive Reservation Reassignment"), any());
    }

    @Test
    void preventiveMoveGivesUpOnThatBookingWhenSomethingElseFails() {
        threatenedBookingWithTwoSubstitutes();
        when(reservationService.moveToAsset(eq(6L), eq(20L), any())).thenThrow(new RuntimeException("cancelled"));

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.preventivelyReassignedCount()).isZero();
        verify(reservationService, never()).moveToAsset(eq(6L), eq(21L), any());
    }

    @Test
    void aFailingEmailNeverBreaksTheSweep() {
        threatenedBookingWithTwoSubstitutes();
        when(reservationService.moveToAsset(eq(6L), eq(20L), any())).thenReturn(true);
        doThrow(new RuntimeException("SES down")).when(notificationService).notifyUser(anyLong(), any(), any());

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        // The booking was moved, so it is counted even though the student could not be told by e-mail.
        assertThat(summary.preventivelyReassignedReservationIds()).containsExactly(6L);
    }
}

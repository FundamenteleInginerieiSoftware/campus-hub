package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.LifecycleSweepSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationLifecycleServiceTest {

    private static final Long ASSET_ID = 10L;
    private static final Long SUB_ASSET_ID = 20L;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationService reservationService;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private NotificationService notificationService;

    private Clock clock;
    private ReservationLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        // 2026-11-02T10:00:00 Europe/Bucharest
        clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        lifecycleService = new ReservationLifecycleService(
                reservationRepository, reservationService, catalogApi, notificationService, clock);
    }

    @Test
    @DisplayName(
            "runLifecycleSweep releases unclaimed reservations as NO_SHOW and proactively moves threatened bookings on OVERDUE assets")
    void runLifecycleSweepHandlesNoShowsAndOverdueCascade() {
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. Unclaimed reservation that started at 09:00 (> 30m ago)
        Reservation noShowRes = new Reservation(
                101L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(noShowRes, "id", 11L);

        // 2. Checked-out reservation whose endTime was 09:30 (now overdue)
        Reservation overdueRes = new Reservation(
                102L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 12, 0));
        ReflectionTestUtils.setField(overdueRes, "id", 22L);
        overdueRes.checkOut(LocalDateTime.of(2026, 11, 2, 8, 5));

        // 3. Threatened reservation on the same asset starting at 10:30 (within 60m horizon)
        Reservation threatenedRes = new Reservation(
                103L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 30),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 1, 15, 0));
        ReflectionTestUtils.setField(threatenedRes, "id", 33L);

        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope 1", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary sub = new AssetSummary(SUB_ASSET_ID, "Scope 2", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);

        when(reservationRepository.findConfirmedBeforeCutoff(now.minusMinutes(30)))
                .thenReturn(List.of(noShowRes));
        when(reservationService.markNoShow(11L)).thenReturn(true);
        when(reservationRepository.findCheckedOutPastEnd(now)).thenReturn(List.of(overdueRes));
        when(reservationService.markOverdue(22L)).thenReturn(true);
        when(reservationRepository.findByStatus(ReservationStatus.OVERDUE)).thenReturn(List.of(overdueRes));
        when(reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, now)).thenReturn(List.of(threatenedRes));
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogApi.findAvailableByModel("Tek-100", ASSET_ID)).thenReturn(List.of(sub));
        when(reservationService.isAssetFree(
                        SUB_ASSET_ID, threatenedRes.getStartTime(), threatenedRes.getEndTime(), 33L))
                .thenReturn(true);
        when(reservationService.moveToAsset(eq(33L), eq(SUB_ASSET_ID), any())).thenReturn(true);

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.noShowReleasedCount()).isEqualTo(1);
        assertThat(summary.markedOverdueCount()).isEqualTo(1);
        assertThat(summary.preventivelyReassignedCount()).isEqualTo(1);
        assertThat(summary.preventivelyReassignedReservationIds()).containsExactly(33L);

        verify(reservationService).moveToAsset(eq(33L), eq(SUB_ASSET_ID), any());
        verify(notificationService).notifyUser(eq(101L), eq("Reservation Marked as No-Show"), any());
        verify(notificationService).notifyUser(eq(102L), eq("Equipment Overdue"), any());
        verify(notificationService).notifyUser(eq(103L), eq("Proactive Reservation Reassignment"), any());
    }

    @Test
    @DisplayName("Cascade does not move threatened booking when substitute is not free")
    void cascadeDoesNotMoveWhenSubstituteNotFree() {
        LocalDateTime now = LocalDateTime.now(clock);

        Reservation overdueRes = new Reservation(
                102L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 12, 0));
        ReflectionTestUtils.setField(overdueRes, "id", 22L);

        Reservation threatenedRes = new Reservation(
                103L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 30),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 1, 15, 0));
        ReflectionTestUtils.setField(threatenedRes, "id", 33L);

        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope 1", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary sub = new AssetSummary(SUB_ASSET_ID, "Scope 2", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);

        when(reservationRepository.findConfirmedBeforeCutoff(now.minusMinutes(30)))
                .thenReturn(List.of());
        when(reservationRepository.findCheckedOutPastEnd(now)).thenReturn(List.of(overdueRes));
        when(reservationService.markOverdue(22L)).thenReturn(true);
        when(reservationRepository.findByStatus(ReservationStatus.OVERDUE)).thenReturn(List.of(overdueRes));
        when(reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, now)).thenReturn(List.of(threatenedRes));
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogApi.findAvailableByModel("Tek-100", ASSET_ID)).thenReturn(List.of(sub));
        when(reservationService.isAssetFree(
                        SUB_ASSET_ID, threatenedRes.getStartTime(), threatenedRes.getEndTime(), 33L))
                .thenReturn(false);

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.markedOverdueCount()).isEqualTo(1);
        assertThat(summary.preventivelyReassignedCount()).isZero();
        verify(reservationService, never()).moveToAsset(anyLong(), anyLong(), any());
        verify(notificationService, never()).notifyUser(eq(103L), any(), any());
    }

    @Test
    @DisplayName("Cascade does not send email when moveToAsset returns false (concurrently cancelled)")
    void cascadeDoesNotEmailWhenMoveToAssetReturnsFalse() {
        LocalDateTime now = LocalDateTime.now(clock);

        Reservation overdueRes = new Reservation(
                102L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 12, 0));
        ReflectionTestUtils.setField(overdueRes, "id", 22L);

        Reservation threatenedRes = new Reservation(
                103L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 30),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 1, 15, 0));
        ReflectionTestUtils.setField(threatenedRes, "id", 33L);

        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope 1", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary sub = new AssetSummary(SUB_ASSET_ID, "Scope 2", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);

        when(reservationRepository.findConfirmedBeforeCutoff(now.minusMinutes(30)))
                .thenReturn(List.of());
        when(reservationRepository.findCheckedOutPastEnd(now)).thenReturn(List.of(overdueRes));
        when(reservationService.markOverdue(22L)).thenReturn(true);
        when(reservationRepository.findByStatus(ReservationStatus.OVERDUE)).thenReturn(List.of(overdueRes));
        when(reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, now)).thenReturn(List.of(threatenedRes));
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogApi.findAvailableByModel("Tek-100", ASSET_ID)).thenReturn(List.of(sub));
        when(reservationService.isAssetFree(
                        SUB_ASSET_ID, threatenedRes.getStartTime(), threatenedRes.getEndTime(), 33L))
                .thenReturn(true);
        when(reservationService.moveToAsset(eq(33L), eq(SUB_ASSET_ID), any())).thenReturn(false);

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.preventivelyReassignedCount()).isZero();
        assertThat(summary.preventivelyReassignedReservationIds()).isEmpty();
        verify(notificationService, never()).notifyUser(eq(103L), any(), any());
    }

    @Test
    @DisplayName("Cascade does not move booking starting beyond the 60-minute horizon")
    void cascadeDoesNotMoveBookingBeyond60MinuteHorizon() {
        LocalDateTime now = LocalDateTime.now(clock); // 10:00

        Reservation overdueRes = new Reservation(
                102L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 12, 0));
        ReflectionTestUtils.setField(overdueRes, "id", 22L);

        // Starts at 11:30 (90 minutes after 10:00 -> beyond the 60-minute horizon)
        Reservation farFutureRes = new Reservation(
                103L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 11, 30),
                LocalDateTime.of(2026, 11, 2, 13, 0),
                LocalDateTime.of(2026, 11, 1, 15, 0));
        ReflectionTestUtils.setField(farFutureRes, "id", 44L);

        when(reservationRepository.findConfirmedBeforeCutoff(now.minusMinutes(30)))
                .thenReturn(List.of());
        when(reservationRepository.findCheckedOutPastEnd(now)).thenReturn(List.of(overdueRes));
        when(reservationService.markOverdue(22L)).thenReturn(true);
        when(reservationRepository.findByStatus(ReservationStatus.OVERDUE)).thenReturn(List.of(overdueRes));
        when(reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, now)).thenReturn(List.of(farFutureRes));

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.markedOverdueCount()).isEqualTo(1);
        assertThat(summary.preventivelyReassignedCount()).isZero();
        verify(catalogApi, never()).getAsset(anyLong());
        verify(reservationService, never()).moveToAsset(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName(
            "Optimistic-lock failure on one reservation skips its email and still processes remaining reservations")
    void sweepIsolatesOptimisticLockFailurePerReservation() {
        LocalDateTime now = LocalDateTime.now(clock);

        Reservation r1 = new Reservation(
                201L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r1, "id", 51L);

        Reservation r2 = new Reservation(
                202L,
                SUB_ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r2, "id", 52L);

        when(reservationRepository.findConfirmedBeforeCutoff(now.minusMinutes(30)))
                .thenReturn(List.of(r1, r2));
        when(reservationService.markNoShow(51L))
                .thenThrow(new ObjectOptimisticLockingFailureException(Reservation.class, 51L));
        when(reservationService.markNoShow(52L)).thenReturn(true);
        when(reservationRepository.findCheckedOutPastEnd(now)).thenReturn(List.of());

        LifecycleSweepSummary summary = lifecycleService.runLifecycleSweep();

        assertThat(summary.noShowReleasedCount()).isEqualTo(1);
        verify(notificationService, never()).notifyUser(eq(201L), any(), any());
        verify(notificationService).notifyUser(eq(202L), eq("Reservation Marked as No-Show"), any());
    }

    @Test
    @DisplayName(
            "Preventive cascade runs across multiple sweeps: second sweep moves later booking for already-OVERDUE asset")
    void cascadeRunsAcrossMultipleSweepsForAlreadyOverdueAsset() {
        LocalDateTime now1 = LocalDateTime.now(clock); // 10:00

        Reservation overdueRes = new Reservation(
                102L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 12, 0));
        ReflectionTestUtils.setField(overdueRes, "id", 22L);

        // Threatened 1: starts at 10:30 (within 60m horizon of 10:00 sweep)
        Reservation threatened1 = new Reservation(
                103L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 30),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 1, 15, 0));
        ReflectionTestUtils.setField(threatened1, "id", 33L);

        // Threatened 2: starts at 11:30 (outside 60m horizon at 10:00, but inside at 11:00)
        Reservation threatened2 = new Reservation(
                104L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 11, 30),
                LocalDateTime.of(2026, 11, 2, 13, 0),
                LocalDateTime.of(2026, 11, 1, 15, 0));
        ReflectionTestUtils.setField(threatened2, "id", 44L);

        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope 1", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary sub = new AssetSummary(SUB_ASSET_ID, "Scope 2", "Tek-100", 1L, 1L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogApi.findAvailableByModel("Tek-100", ASSET_ID)).thenReturn(List.of(sub));

        // --- Sweep 1: at 10:00 ---
        when(reservationRepository.findConfirmedBeforeCutoff(now1.minusMinutes(30)))
                .thenReturn(List.of());
        when(reservationRepository.findCheckedOutPastEnd(now1)).thenReturn(List.of(overdueRes));
        when(reservationService.markOverdue(22L)).thenReturn(true);
        when(reservationRepository.findByStatus(ReservationStatus.OVERDUE)).thenReturn(List.of(overdueRes));
        when(reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, now1))
                .thenReturn(List.of(threatened1, threatened2));
        when(reservationService.isAssetFree(SUB_ASSET_ID, threatened1.getStartTime(), threatened1.getEndTime(), 33L))
                .thenReturn(true);
        when(reservationService.moveToAsset(eq(33L), eq(SUB_ASSET_ID), any())).thenReturn(true);

        LifecycleSweepSummary sweep1 = lifecycleService.runLifecycleSweep();

        assertThat(sweep1.markedOverdueCount()).isEqualTo(1);
        assertThat(sweep1.preventivelyReassignedCount()).isEqualTo(1);
        assertThat(sweep1.preventivelyReassignedReservationIds()).containsExactly(33L);

        // --- Sweep 2: at 11:00 (60 minutes later, equipment still OVERDUE) ---
        Clock clock2 = Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneId.of("Europe/Bucharest"));
        ReservationLifecycleService service2 = new ReservationLifecycleService(
                reservationRepository, reservationService, catalogApi, notificationService, clock2);

        LocalDateTime now2 = LocalDateTime.now(clock2); // 11:00
        when(reservationRepository.findConfirmedBeforeCutoff(now2.minusMinutes(30)))
                .thenReturn(List.of());
        // No new expired checkouts, reservation 22L is already OVERDUE
        when(reservationRepository.findCheckedOutPastEnd(now2)).thenReturn(List.of());
        when(reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, now2)).thenReturn(List.of(threatened2));
        when(reservationService.isAssetFree(SUB_ASSET_ID, threatened2.getStartTime(), threatened2.getEndTime(), 44L))
                .thenReturn(true);
        when(reservationService.moveToAsset(eq(44L), eq(SUB_ASSET_ID), any())).thenReturn(true);

        LifecycleSweepSummary sweep2 = service2.runLifecycleSweep();

        assertThat(sweep2.markedOverdueCount()).isZero(); // Not marked again
        assertThat(sweep2.preventivelyReassignedCount()).isEqualTo(1); // Second booking moved!
        assertThat(sweep2.preventivelyReassignedReservationIds()).containsExactly(44L);

        verify(notificationService).notifyUser(eq(102L), eq("Equipment Overdue"), any());
        verify(notificationService).notifyUser(eq(103L), eq("Proactive Reservation Reassignment"), any());
        verify(notificationService).notifyUser(eq(104L), eq("Proactive Reservation Reassignment"), any());
    }
}

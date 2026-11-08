package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateHoldRequest;
import com.campus.reservation.web.ReservationHoldResponse;
import com.campus.reservation.web.ReservationResponse;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationHoldServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ASSET_ID = 100L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0); // Monday
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 2, 16, 0);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ReservationHoldRepository reservationHoldRepository;

    private Clock clock;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        reservationService = new ReservationService(
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                Optional.of(reservationHoldRepository));
    }

    @Test
    @DisplayName("createHold: acquires a 1-minute hold on a free slot")
    void createHold_success() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(0L);
        when(reservationRepository.countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationHoldRepository.save(any(ReservationHold.class))).thenAnswer(inv -> {
            ReservationHold h = inv.getArgument(0);
            ReflectionTestUtils.setField(h, "id", 1L);
            return h;
        });

        ReservationHoldResponse response = reservationService.createHold(new CreateHoldRequest(ASSET_ID, START, END));

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(response.assetId()).isEqualTo(ASSET_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.expiresAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(response.remainingSeconds()).isGreaterThan(0);

        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(auditService).record(eq(USER_ID), eq("HOLD_ACQUIRED"), eq("ReservationHold"), eq(1L), any());
    }

    @Test
    @DisplayName("createHold: rejects if slot is held by another student")
    void createHold_rejectsIfHeldByAnother() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.createHold(new CreateHoldRequest(ASSET_ID, START, END)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("temporarily held by another student");

        verify(reservationHoldRepository, never()).save(any());
    }

    @Test
    @DisplayName("createHold: rejects if student already has 1 active hold")
    void createHold_rejectsIfActiveHoldLimitReached() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(1L);

        assertThatThrownBy(() -> reservationService.createHold(new CreateHoldRequest(ASSET_ID, START, END)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already have an active reservation hold");
    }

    @Test
    @DisplayName("confirmHold: converts active hold into confirmed reservation and notifies user")
    void confirmHold_success() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 55L);
            return r;
        });

        ReservationResponse response = reservationService.confirmHold(1L);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(55L);
        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);

        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Confirmed"), any());
        verify(auditService).record(eq(USER_ID), eq("CREATE_RESERVATION"), eq("Reservation"), eq(55L), any());
    }

    @Test
    @DisplayName("confirmHold: rejects if hold has expired")
    void confirmHold_rejectsIfExpired() {
        // Expired 10 seconds ago
        ReservationHold hold =
                new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.minusSeconds(10), NOW.minusMinutes(1));
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> reservationService.confirmHold(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("The reservation hold has expired");

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    @DisplayName("confirmHold: IDOR protection throws 404 if hold belongs to another user")
    void confirmHold_idorProtectionThrows404() {
        ReservationHold hold = new ReservationHold(ASSET_ID, 999L, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> reservationService.confirmHold(1L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation hold not found");
    }

    @Test
    @DisplayName("releaseHold: transitions active hold to RELEASED and records audit")
    void releaseHold_success() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(reservationHoldRepository.save(any(ReservationHold.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationHoldResponse response = reservationService.releaseHold(1L);

        assertThat(response.status()).isEqualTo(HoldStatus.RELEASED);
        verify(auditService).record(eq(USER_ID), eq("HOLD_RELEASED"), eq("ReservationHold"), eq(1L), any());
    }

    @Test
    @DisplayName("getMyActiveHolds: returns list of unexpired active holds for user")
    void getMyActiveHolds_returnsList() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusSeconds(50), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationHoldRepository.findByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of(hold));

        List<ReservationHoldResponse> holds = reservationService.getMyActiveHolds();

        assertThat(holds).hasSize(1);
        assertThat(holds.get(0).id()).isEqualTo(1L);
        assertThat(holds.get(0).remainingSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("sweepExpiredHolds: calls expireOldHolds on repository")
    void sweepExpiredHolds_callsRepository() {
        when(reservationHoldRepository.expireOldHolds(any())).thenReturn(3);

        int count = reservationService.sweepExpiredHolds(NOW);

        assertThat(count).isEqualTo(3);
        verify(reservationHoldRepository).expireOldHolds(NOW);
    }
}

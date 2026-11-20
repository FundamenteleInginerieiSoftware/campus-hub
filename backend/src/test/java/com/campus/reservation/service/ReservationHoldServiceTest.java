package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.ForbiddenException;
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
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
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

    @Mock
    private UserApi userApi;

    private Clock clock;
    private HoldService holdService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        holdService = new HoldService(
                reservationHoldRepository,
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                userApi,
                clock);
        lenient().when(userApi.findActiveRole(anyLong())).thenReturn(Optional.of(Role.STUDENT));
    }

    @Test
    @DisplayName("createHold: acquires a 1-minute hold on a free slot")
    void createHold_success() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(0L);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationHoldRepository.save(any(ReservationHold.class))).thenAnswer(inv -> {
            ReservationHold h = inv.getArgument(0);
            ReflectionTestUtils.setField(h, "id", 1L);
            return h;
        });

        ReservationHoldResponse response = holdService.createHold(new CreateHoldRequest(ASSET_ID, START, END));

        assertThat(response)
                .as("hold response: creation must return non-null hold response; see HoldService.createHold")
                .isNotNull();
        assertThat(response.id())
                .as("hold response: created hold must have assigned ID; see HoldService.createHold")
                .isEqualTo(1L);
        assertThat(response.status())
                .as("hold status: newly acquired hold must be ACTIVE; see HoldService.createHold")
                .isEqualTo(HoldStatus.ACTIVE);
        assertThat(response.assetId())
                .as("hold asset: hold must match requested asset ID; see HoldService.createHold")
                .isEqualTo(ASSET_ID);
        assertThat(response.userId())
                .as("hold owner: hold must match authenticated student ID; see HoldService.createHold")
                .isEqualTo(USER_ID);
        assertThat(response.expiresAt())
                .as("hold expiry: hold must expire exactly 1 minute after creation; see HoldService.createHold")
                .isEqualTo(NOW.plusMinutes(1));
        assertThat(response.remainingSeconds())
                .as(
                        "hold remaining seconds: active hold must report positive remaining time; see HoldService.createHold")
                .isGreaterThan(0);

        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(auditService).record(eq(USER_ID), eq("HOLD_ACQUIRED"), eq("ReservationHold"), eq(1L), any());
    }

    @Test
    @DisplayName("createHold: rejects if slot is held by another student")
    void createHold_rejectsIfHeldByAnother() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> holdService.createHold(new CreateHoldRequest(ASSET_ID, START, END)))
                .as("concurrency: slot held by another student cannot be held concurrently; see HoldService.createHold")
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
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(1L);

        assertThatThrownBy(() -> holdService.createHold(new CreateHoldRequest(ASSET_ID, START, END)))
                .as("quota limit: student with an active hold cannot acquire another hold; see HoldService.createHold")
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
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 55L);
            return r;
        });

        ReservationResponse response = holdService.confirmHold(1L);

        assertThat(response)
                .as("confirm hold: confirmed reservation response must not be null; see HoldService.confirmHold")
                .isNotNull();
        assertThat(response.id())
                .as("confirm hold: confirmed reservation must have ID; see HoldService.confirmHold")
                .isEqualTo(55L);
        assertThat(response.status())
                .as("confirm hold: confirmed reservation must have CONFIRMED status; see HoldService.confirmHold")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(hold.getStatus())
                .as("hold status: confirmed hold must transition to CONFIRMED; see HoldService.confirmHold")
                .isEqualTo(HoldStatus.CONFIRMED);

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

        assertThatThrownBy(() -> holdService.confirmHold(1L))
                .as(
                        "expiry: attempting to confirm an expired hold must throw ConflictException; see HoldService.confirmHold")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("The reservation hold has expired");

        assertThat(hold.getStatus())
                .as(
                        "hold status: expired hold must not be mutated before rollback on confirm; see ReservationHold.confirm")
                .isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    @DisplayName("confirmHold: IDOR protection throws 404 if hold belongs to another user")
    void confirmHold_idorProtectionThrows404() {
        ReservationHold hold = new ReservationHold(ASSET_ID, 999L, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.confirmHold(1L))
                .as(
                        "IDOR protection: student attempting to confirm another user's hold must get 404 Not Found; see HoldService.confirmHold")
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

        ReservationHoldResponse response = holdService.releaseHold(1L);

        assertThat(response.status())
                .as("release hold: released hold must transition to RELEASED; see HoldService.releaseHold")
                .isEqualTo(HoldStatus.RELEASED);
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

        List<ReservationHoldResponse> holds = holdService.getMyActiveHolds();

        assertThat(holds)
                .as("my holds: must return list of user's active holds; see HoldService.getMyActiveHolds")
                .hasSize(1);
        assertThat(holds.get(0).id())
                .as("my holds: must match active hold ID; see HoldService.getMyActiveHolds")
                .isEqualTo(1L);
        assertThat(holds.get(0).remainingSeconds())
                .as("my holds: active hold must report positive remaining seconds; see HoldService.getMyActiveHolds")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("sweepExpiredHolds: calls expireOldHolds on repository")
    void sweepExpiredHolds_callsRepository() {
        when(reservationHoldRepository.expireOldHolds(any())).thenReturn(3);

        int count = holdService.sweepExpiredHolds(NOW);

        assertThat(count)
                .as(
                        "sweep expired holds: must return count of expired rows reported by repository; see HoldService.sweepExpiredHolds")
                .isEqualTo(3);
        verify(reservationHoldRepository).expireOldHolds(NOW);
    }

    @Test
    @DisplayName("createHold: quota boundary - activeReservations == 1 allows hold creation")
    void createHold_boundaryQuota_oneActiveAllowsHold() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(0L);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L); // Boundary: 1 active reservation (max quota is 2)
        when(reservationHoldRepository.save(any(ReservationHold.class))).thenAnswer(inv -> {
            ReservationHold h = inv.getArgument(0);
            ReflectionTestUtils.setField(h, "id", 1L);
            return h;
        });

        ReservationHoldResponse response = holdService.createHold(new CreateHoldRequest(ASSET_ID, START, END));

        assertThat(response)
                .as(
                        "quota boundary: student with 1 active reservation is allowed to hold (limit is 2); see HoldService.createHold")
                .isNotNull();
        assertThat(response.id())
                .as("quota boundary: hold ID must be returned; see HoldService.createHold")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("createHold: quota boundary - activeReservations == 2 rejects hold creation")
    void createHold_boundaryQuota_twoActiveRejectsHold() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.countByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(0L);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L); // Boundary: 2 active reservations -> limit reached

        assertThatThrownBy(() -> holdService.createHold(new CreateHoldRequest(ASSET_ID, START, END)))
                .as(
                        "quota boundary: student with 2 active reservations cannot hold (limit is 2); see HoldService.createHold")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Student cannot exceed active reservation quota");
    }

    @Test
    @DisplayName("createHold: slot validation - rejects when end time is before start time")
    void createHold_rejectsWhenEndTimeBeforeStartTime() {
        assertThatThrownBy(() -> holdService.createHold(new CreateHoldRequest(ASSET_ID, END, START)))
                .as(
                        "slot validation: end time before start time must throw BusinessRuleException; see HoldService.createHold")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("End time must be after start time");
    }

    @Test
    @DisplayName("createHold: slot validation - rejects when slot is outside opening hours")
    void createHold_rejectsWhenOutsideOpeningHours() {
        // Outside opening hours (e.g. 20:30)
        LocalDateTime lateStart = LocalDateTime.of(2026, 11, 2, 20, 30);
        LocalDateTime lateEnd = LocalDateTime.of(2026, 11, 2, 21, 30);
        assertThatThrownBy(() -> holdService.createHold(new CreateHoldRequest(ASSET_ID, lateStart, lateEnd)))
                .as(
                        "operating hours: hold outside 08:00-20:00 must throw BusinessRuleException; see HoldService.createHold")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("within opening hours");
    }

    @Test
    @DisplayName("confirmHold: quota boundary - activeCount == 1 allows confirmation")
    void confirmHold_boundaryQuota_oneActiveAllowsConfirm() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L); // Boundary: 1 active reservation (limit is 2)
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 55L);
            return r;
        });

        ReservationResponse response = holdService.confirmHold(1L);

        assertThat(response)
                .as(
                        "quota boundary: student with 1 active reservation can confirm hold (limit is 2); see HoldService.confirmHold")
                .isNotNull();
        assertThat(response.id())
                .as("quota boundary: confirmed reservation ID must be returned; see HoldService.confirmHold")
                .isEqualTo(55L);
    }

    @Test
    @DisplayName("confirmHold: quota boundary - activeCount == 2 rejects confirmation")
    void confirmHold_boundaryQuota_twoActiveRejectsConfirm() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L); // Boundary: 2 active reservations -> limit reached

        assertThatThrownBy(() -> holdService.confirmHold(1L))
                .as(
                        "quota boundary: student with 2 active reservations cannot confirm hold (limit is 2); see HoldService.confirmHold")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Student cannot have more than 2 active reservations");
    }

    @Test
    @DisplayName("confirmHold: admin bypasses quota check for own hold")
    void confirmHold_adminBypassesQuota() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(true); // Admin bypasses quota
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 55L);
            return r;
        });

        ReservationResponse response = holdService.confirmHold(1L);

        assertThat(response)
                .as(
                        "admin override: admin can confirm hold even if quota would normally apply; see HoldService.confirmHold")
                .isNotNull();
        assertThat(response.id())
                .as("admin override: confirmed reservation ID must be returned; see HoldService.confirmHold")
                .isEqualTo(55L);
        verify(reservationRepository, never()).countBookingSessionsByUserIdAndStatusIn(anyLong(), any());
    }

    @Test
    @DisplayName("confirmHold: rejects if slot start time has already passed (#228 point 9)")
    void confirmHold_rejectsIfStartTimeHasPassed() {
        LocalDateTime pastStart = NOW.minusMinutes(10);
        LocalDateTime pastEnd = NOW.plusHours(1);
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, pastStart, pastEnd, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.confirmHold(1L))
                .as("start time check: hold cannot be confirmed after slot start time; see HoldService.confirmHold")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot book a reservation in the past");
    }

    @Test
    @DisplayName("confirmHold: admin confirming notifies student owner, not admin (#228 point 9)")
    void confirmHold_adminConfirmingNotifiesStudentOwner() {
        Long studentId = 55L;
        ReservationHold hold = new ReservationHold(ASSET_ID, studentId, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID); // Admin ID
        when(currentUser.isAdmin()).thenReturn(true);
        when(userApi.findActiveRole(studentId)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 999L);
            return r;
        });

        ReservationResponse response = holdService.confirmHold(1L);

        assertThat(response)
                .as("admin confirmation: returns created reservation response; see HoldService.confirmHold")
                .isNotNull();
        verify(notificationService).notifyUser(eq(studentId), eq("Reservation Confirmed"), any());
        verify(notificationService, never()).notifyUser(eq(USER_ID), eq("Reservation Confirmed"), any());
    }

    @Test
    @DisplayName("confirmHold: admin confirming student hold rejects when student quota exceeded (#228 point 9)")
    void confirmHold_adminConfirmingStudentHoldRejectsWhenStudentQuotaExceeded() {
        Long studentId = 55L;
        ReservationHold hold = new ReservationHold(ASSET_ID, studentId, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID); // Admin ID
        when(currentUser.isAdmin()).thenReturn(true);
        when(userApi.findActiveRole(studentId)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        studentId, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);

        assertThatThrownBy(() -> holdService.confirmHold(1L))
                .as(
                        "quota limit: student cannot exceed 2 active reservations even on admin confirmation; see HoldService.confirmHold")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Student cannot have more than 2 active reservations");
    }

    @Test
    @DisplayName("confirmHold: throws 404 HOLD_NOT_FOUND when hold does not exist")
    void confirmHold_notFound_throws404() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationHoldRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.confirmHold(999L))
                .as("existence: confirming a non-existent hold must throw HOLD_NOT_FOUND; see HoldService.confirmHold")
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "HOLD_NOT_FOUND");
    }

    @Test
    @DisplayName("confirmHold: admin confirming another admin's hold skips student quota check")
    void confirmHold_adminConfirmingAnotherAdminsHoldSkipsQuotaCheck() {
        Long otherAdminId = 77L;
        ReservationHold hold = new ReservationHold(ASSET_ID, otherAdminId, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(true);
        when(userApi.findActiveRole(otherAdminId)).thenReturn(Optional.of(Role.ADMIN));
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 56L);
            return r;
        });

        ReservationResponse response = holdService.confirmHold(1L);

        assertThat(response.id())
                .as(
                        "admin-owned hold confirmed by another admin must bypass student quota; see HoldService.confirmHold")
                .isEqualTo(56L);
        verify(reservationRepository, never()).lockUserQuota(any());
        verify(reservationRepository, never()).countBookingSessionsByUserIdAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("releaseHold: admin can release another user's hold")
    void releaseHold_byAdmin_success() {
        ReservationHold hold = new ReservationHold(ASSET_ID, 999L, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(true); // Admin can release any user's hold
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(reservationHoldRepository.save(any(ReservationHold.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationHoldResponse response = holdService.releaseHold(1L);

        assertThat(response.status())
                .as("admin override: admin can release another student's hold; see HoldService.releaseHold")
                .isEqualTo(HoldStatus.RELEASED);
        verify(auditService).record(eq(USER_ID), eq("HOLD_RELEASED"), eq("ReservationHold"), eq(1L), any());
    }

    @Test
    @DisplayName("releaseHold: non-owner student throws 404")
    void releaseHold_nonOwner_throws404() {
        ReservationHold hold = new ReservationHold(ASSET_ID, 999L, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 1L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.releaseHold(1L))
                .as(
                        "IDOR protection: non-owner student releasing another user's hold must receive 404 Not Found; see HoldService.releaseHold")
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation hold not found");
    }

    @Test
    @DisplayName("releaseHold: hold not found throws 404")
    void releaseHold_notFound_throws404() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationHoldRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.releaseHold(1L))
                .as("existence: releasing non-existent hold must receive 404 Not Found; see HoldService.releaseHold")
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation hold not found");
    }

    @Test
    @DisplayName("createHold: rejects if cooldown is active for same slot (#240 point 4)")
    void createHold_rejectsIfCooldownActive() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(eq(ASSET_ID), any(), any(), eq(-1L)))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), any(), any(), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.existsRecentHoldOverlap(eq(USER_ID), eq(ASSET_ID), any(), any(), any()))
                .thenReturn(true);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        CreateHoldRequest req = new CreateHoldRequest(ASSET_ID, start, end);

        assertThatThrownBy(() -> holdService.createHold(req))
                .as(
                        "hold cooldown: student cannot repeatedly hold and release overlapping slots; see HoldService.createHold")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("You recently held an overlapping slot on this equipment");
    }

    @Test
    @DisplayName("createHold: rejects disabled student after acquiring quota lock (#229)")
    void createHold_rejectsDisabledUserAfterLockingQuota() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(START), eq(END), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.existsRecentHoldOverlap(eq(USER_ID), eq(ASSET_ID), any(), any(), any()))
                .thenReturn(false);
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.empty());

        CreateHoldRequest req = new CreateHoldRequest(ASSET_ID, START, END);

        assertThatThrownBy(() -> holdService.createHold(req))
                .as("disabled user after lockUserQuota must be rejected with 403 USER_DISABLED; "
                        + "see HoldService.createHold")
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("code", "USER_DISABLED");

        var order = inOrder(reservationRepository, userApi);
        order.verify(reservationRepository).lockUserQuota(USER_ID);
        order.verify(userApi).findActiveRole(USER_ID);
        verify(reservationHoldRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirmHold: rejects disabled hold owner after acquiring quota lock (#229)")
    void confirmHold_rejectsDisabledHoldOwnerAfterLockingQuota() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusMinutes(1), NOW);
        ReflectionTestUtils.setField(hold, "id", 77L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationHoldRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(hold));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, START, END, -1L)).thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(
                        eq(ASSET_ID), eq(START), eq(END), any(), eq(77L), eq(USER_ID)))
                .thenReturn(false);
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.confirmHold(77L))
                .as("disabled hold owner after lockUserQuota must be rejected with 403 USER_DISABLED; "
                        + "see HoldService.confirmHold")
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("code", "USER_DISABLED");

        var order = inOrder(reservationRepository, userApi);
        order.verify(reservationRepository).lockUserQuota(USER_ID);
        order.verify(userApi).findActiveRole(USER_ID);
        verify(reservationRepository, never()).save(any());
    }
}

package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ReservationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ASSET_ID = 10L;

    // Fixed clock: 2026-11-02T08:00:00Z -> In Europe/Bucharest (UTC+2 in winter) this is 2026-11-02T10:00:00 (Monday)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService auditService;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService =
                new ReservationService(reservationRepository, catalogApi, currentUser, auditService, clock);
    }

    @Test
    @DisplayName("Happy path: creates reservation and enforces strict lock-before-check order")
    void createsReservationWithStrictLockOrder() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.assetId()).isEqualTo(ASSET_ID);
        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Strict lock order verification
        InOrder inOrder = inOrder(catalogApi, reservationRepository, auditService);
        inOrder.verify(catalogApi).lockForBooking(ASSET_ID);
        inOrder.verify(catalogApi).isBookable(ASSET_ID);
        inOrder.verify(reservationRepository)
                .existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L);
        inOrder.verify(reservationRepository).countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES);
        inOrder.verify(reservationRepository).save(any(Reservation.class));
        inOrder.verify(auditService).record(eq(USER_ID), eq("CREATE_RESERVATION"), eq("Reservation"), any(), any());
    }

    @Test
    @DisplayName("Admin user bypasses active reservation limit check")
    void adminBypassesActiveReservationLimit() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(true);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);

        assertThat(response).isNotNull();
        verify(reservationRepository, never()).countByUserIdAndStatusIn(anyLong(), any());
    }

    @Test
    @DisplayName("Rejects when end time is not after start time (INVALID_TIME_RANGE)")
    void rejectsWhenEndTimeNotAfterStartTime() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 14, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TIME_RANGE");

        verify(catalogApi, never()).lockForBooking(anyLong());
    }

    @Test
    @DisplayName("Rejects when booking is in the past (BOOKING_IN_PAST)")
    void rejectsWhenBookingInPast() {
        // Clock is at 10:00 on 2026-11-02
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 11, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "BOOKING_IN_PAST");

        verify(catalogApi, never()).lockForBooking(anyLong());
    }

    @Test
    @DisplayName("Rejects when slot minutes are not 0 or 30 (SLOT_NOT_ALIGNED)")
    void rejectsWhenMinutesNotAligned() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 15);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 15, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "SLOT_NOT_ALIGNED");
    }

    @Test
    @DisplayName("Rejects when slot seconds are not zero (SLOT_NOT_ALIGNED)")
    void rejectsWhenSecondsNotAligned() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0, 5);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 15, 0, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "SLOT_NOT_ALIGNED");
    }

    @Test
    @DisplayName("Rejects when duration is greater than 4 hours (INVALID_DURATION)")
    void rejectsWhenDurationExceeds4Hours() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 18, 30); // 4.5 hours
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DURATION");
    }

    @Test
    @DisplayName("Rejects when booking is more than 14 days in advance (BOOKING_TOO_FAR_AHEAD)")
    void rejectsWhenBookingTooFarAhead() {
        // Clock is at 2026-11-02 -> 15 days ahead is 2026-11-17
        LocalDateTime start = LocalDateTime.of(2026, 11, 17, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 17, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "BOOKING_TOO_FAR_AHEAD");
    }

    @Test
    @DisplayName("Rejects when start and end span across multiple days (OUTSIDE_OPENING_HOURS)")
    void rejectsWhenCrossingDays() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 23, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 1, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Rejects when booking falls on a weekend (OUTSIDE_OPENING_HOURS)")
    void rejectsWhenOnWeekend() {
        // 2026-11-07 is Saturday
        LocalDateTime start = LocalDateTime.of(2026, 11, 7, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 7, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Rejects when before opening hours (OUTSIDE_OPENING_HOURS)")
    void rejectsWhenBeforeOpeningHours() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 7, 30);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 9, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Rejects when after closing hours (OUTSIDE_OPENING_HOURS)")
    void rejectsWhenAfterClosingHours() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 19, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 20, 30);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Propagates NotFoundException when asset row lock fails")
    void propagatesNotFoundWhenLockFails() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        doThrow(new NotFoundException("Asset not found")).when(catalogApi).lockForBooking(ASSET_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req)).isInstanceOf(NotFoundException.class);

        verify(catalogApi, never()).isBookable(anyLong());
        verify(reservationRepository, never()).existsOverlap(anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("Rejects when asset is not available for booking (ASSET_NOT_BOOKABLE)")
    void rejectsWhenAssetNotBookable() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "ASSET_NOT_BOOKABLE");

        verify(reservationRepository, never()).existsOverlap(anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("Rejects when requested slot overlaps with an active reservation (RESERVATION_CONFLICT)")
    void rejectsWhenSlotOverlaps() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_CONFLICT");

        verify(reservationRepository, never()).countByUserIdAndStatusIn(anyLong(), any());
    }

    @Test
    @DisplayName("Rejects when student has reached active reservation limit (RESERVATION_LIMIT_REACHED)")
    void rejectsWhenActiveLimitReached() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_LIMIT_REACHED");

        verify(reservationRepository, never()).save(any());
    }
}

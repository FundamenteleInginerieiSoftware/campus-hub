package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateReservationRequest;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    @DisplayName("getMyReservations returns paginated user reservations without status filter")
    void getMyReservationsWithoutStatus() {
        when(currentUser.id()).thenReturn(USER_ID);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        when(reservationRepository.findByUserIdOrderByStartTimeAsc(eq(USER_ID), any()))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1));

        PageResponse<ReservationResponse> result = reservationService.getMyReservations(null, 0, 10);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).userId()).isEqualTo(USER_ID);
        verify(reservationRepository).findByUserIdOrderByStartTimeAsc(eq(USER_ID), any());
    }

    @Test
    @DisplayName("getMyReservations returns paginated user reservations with status filter")
    void getMyReservationsWithStatus() {
        when(currentUser.id()).thenReturn(USER_ID);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        when(reservationRepository.findByUserIdAndStatusOrderByStartTimeAsc(
                        eq(USER_ID), eq(ReservationStatus.CONFIRMED), any()))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1));

        PageResponse<ReservationResponse> result =
                reservationService.getMyReservations(ReservationStatus.CONFIRMED, 0, 10);

        assertThat(result.content()).hasSize(1);
        verify(reservationRepository)
                .findByUserIdAndStatusOrderByStartTimeAsc(eq(USER_ID), eq(ReservationStatus.CONFIRMED), any());
    }

    @Test
    @DisplayName("getById allows owner to view own reservation")
    void getByIdOwnerAllowed() {
        when(currentUser.id()).thenReturn(USER_ID);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        // The database sets the id on insert; there's no database here, so set it by hand.
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        ReservationResponse response = reservationService.getById(1L);

        assertThat(response.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("getById allows admin to view another student's reservation")
    void getByIdAdminAllowed() {
        when(currentUser.id()).thenReturn(999L);
        when(currentUser.isAdmin()).thenReturn(true);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        // The database sets the id on insert; there's no database here, so set it by hand.
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        ReservationResponse response = reservationService.getById(1L);

        assertThat(response.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("getById enforces IDOR protection: throws 404 NotFound when another student views it")
    void getByIdIdorProtectionThrows404() {
        when(currentUser.id()).thenReturn(999L); // Student B
        when(currentUser.isAdmin()).thenReturn(false);
        Reservation r = new Reservation(
                USER_ID, // Student A
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.getById(1L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_FOUND")
                .hasMessage("Reservation not found: 1");
    }

    @Test
    @DisplayName("cancelReservation succeeds for owner before start time")
    void cancelReservationOwnerBeforeStartSucceeds() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0), // Start is 14:00 (clock is 10:00)
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        ReservationResponse response = reservationService.cancelReservation(1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(auditService).record(eq(USER_ID), eq("CANCEL_RESERVATION"), eq("Reservation"), any(), any());
    }

    @Test
    @DisplayName("cancelReservation succeeds for admin even after start time")
    void cancelReservationAdminAfterStartSucceeds() {
        when(currentUser.id()).thenReturn(999L);
        when(currentUser.isAdmin()).thenReturn(true);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0), // Start was 09:00 (clock is 10:00)
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        ReservationResponse response = reservationService.cancelReservation(1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelReservation rejects student trying to cancel after start time")
    void cancelReservationStudentAfterStartFails() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0), // In past relative to clock 10:00
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.cancelReservation(1L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CANCELLATION_NOT_ALLOWED");
    }

    @Test
    @DisplayName("cancelReservation rejects cancelling a non-CONFIRMED reservation")
    void cancelReservationNonConfirmedFails() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        r.checkOut(LocalDateTime.of(2026, 11, 2, 14, 0)); // status is now CHECKED_OUT
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.cancelReservation(1L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CANCELLATION_NOT_ALLOWED");
    }

    @Test
    @DisplayName("cancelReservation IDOR protection throws 404 for other student")
    void cancelReservationIdorProtectionThrows404() {
        when(currentUser.id()).thenReturn(999L);
        when(currentUser.isAdmin()).thenReturn(false);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        // The database sets the id on insert; there's no database here, so set it by hand.
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.cancelReservation(1L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_FOUND")
                .hasMessage("Reservation not found: 1");
    }

    @Test
    @DisplayName("ReservationApi: findUpcomingActiveByAsset maps confirmed reservations")
    void findUpcomingActiveByAssetMapsProperly() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        LocalDateTime from = LocalDateTime.of(2026, 11, 2, 10, 0);
        when(reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, from)).thenReturn(List.of(r));

        List<ReservationSummary> summaries = reservationService.findUpcomingActiveByAsset(ASSET_ID, from);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).assetId()).isEqualTo(ASSET_ID);
        assertThat(summaries.get(0).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("ReservationApi: isAssetFree returns correct boolean based on overlap query")
    void isAssetFreeChecksOverlap() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        assertThat(reservationService.isAssetFree(ASSET_ID, start, end, null)).isTrue();

        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, 5L))
                .thenReturn(true);
        assertThat(reservationService.isAssetFree(ASSET_ID, start, end, 5L)).isFalse();
    }

    @Test
    @DisplayName("ReservationApi: moveToAsset successfully moves asset when substitute is free")
    void moveToAssetSucceedsWhenFree() {
        Long newAssetId = 20L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        // The database sets the id on insert; there's no database here, so set it by hand.
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(newAssetId)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        eq(newAssetId), any(), any(), eq(ReservationStatus.ACTIVE_STATUSES), anyLong()))
                .thenReturn(false);

        reservationService.moveToAsset(1L, newAssetId, "Damaged in lab");

        assertThat(r.getAssetId()).isEqualTo(newAssetId);
        assertThat(r.getOriginalAssetId()).isEqualTo(ASSET_ID);
        verify(catalogApi).lockForBooking(newAssetId);
        verify(auditService).record(isNull(), eq("REASSIGN_RESERVATION"), eq("Reservation"), any(), any());
    }

    @Test
    @DisplayName("ReservationApi: moveToAsset throws SubstituteNoLongerFreeException when substitute not free")
    void moveToAssetFailsWhenNotFree() {
        Long newAssetId = 20L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        // The database sets the id on insert; there's no database here, so set it by hand.
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(newAssetId)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.moveToAsset(1L, newAssetId, "Damaged"))
                .isInstanceOf(SubstituteNoLongerFreeException.class);
    }

    @Test
    @DisplayName("ReservationApi: moveToAsset returns false if reservation not CONFIRMED")
    void moveToAssetSilentlyReturnsWhenNotConfirmed() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        r.cancel(LocalDateTime.of(2026, 11, 2, 11, 0), "Cancelled");
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        boolean moved = reservationService.moveToAsset(1L, 20L, "Damaged");

        assertThat(moved).isFalse();
        verify(catalogApi, never()).lockForBooking(anyLong());
    }

    @Test
    @DisplayName("ReservationApi: forceCancel returns false if reservation not CONFIRMED")
    void forceCancelReturnsFalseWhenNotConfirmed() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        r.cancel(LocalDateTime.of(2026, 11, 2, 11, 0), "Cancelled");
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        boolean cancelled = reservationService.forceCancel(1L, "Damaged");

        assertThat(cancelled).isFalse();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("markNoShow and markOverdue transition single reservations and return false when status changed")
    void markNoShowAndMarkOverdueTransitionInOwnTransaction() {
        Reservation confirmed = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(confirmed, "id", 10L);
        when(reservationRepository.findById(10L)).thenReturn(Optional.of(confirmed));

        assertThat(reservationService.markNoShow(10L)).isTrue();
        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservationService.markNoShow(10L)).isFalse();

        Reservation checkedOut = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 12, 0));
        ReflectionTestUtils.setField(checkedOut, "id", 11L);
        checkedOut.checkOut(LocalDateTime.of(2026, 11, 2, 8, 5));
        when(reservationRepository.findById(11L)).thenReturn(Optional.of(checkedOut));

        assertThat(reservationService.markOverdue(11L)).isTrue();
        assertThat(checkedOut.getStatus()).isEqualTo(ReservationStatus.OVERDUE);
        assertThat(reservationService.markOverdue(11L)).isFalse();
    }

    @Test
    @DisplayName("ReservationApi: forceCancel successfully cancels confirmed reservation")
    void forceCancelSucceedsForConfirmed() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        // The database sets the id on insert; there's no database here, so set it by hand.
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        reservationService.forceCancel(1L, "Damaged beyond repair");

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.FORCE_CANCELLED);
        verify(auditService).record(isNull(), eq("FORCE_CANCEL_RESERVATION"), eq("Reservation"), any(), any());
    }

    @Test
    @DisplayName("ReservationApi: findCurrentCheckout maps checked-out or overdue reservation")
    void findCurrentCheckoutMapsProperly() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        r.checkOut(LocalDateTime.of(2026, 11, 2, 14, 0));
        when(reservationRepository.findFirstByAssetIdAndStatusIn(
                        ASSET_ID, java.util.EnumSet.of(ReservationStatus.CHECKED_OUT, ReservationStatus.OVERDUE)))
                .thenReturn(Optional.of(r));

        Optional<ReservationSummary> current = reservationService.findCurrentCheckout(ASSET_ID);

        assertThat(current).isPresent();
        assertThat(current.get().status()).isEqualTo(ReservationStatus.CHECKED_OUT);
    }

    @Test
    @DisplayName("ReservationApi: findCurrentCheckout maps overdue reservation")
    void findCurrentCheckoutMapsOverdueProperly() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        r.checkOut(LocalDateTime.of(2026, 11, 2, 14, 0));
        r.markOverdue();
        when(reservationRepository.findFirstByAssetIdAndStatusIn(
                        ASSET_ID, java.util.EnumSet.of(ReservationStatus.CHECKED_OUT, ReservationStatus.OVERDUE)))
                .thenReturn(Optional.of(r));

        Optional<ReservationSummary> current = reservationService.findCurrentCheckout(ASSET_ID);

        assertThat(current).isPresent();
        assertThat(current.get().status()).isEqualTo(ReservationStatus.OVERDUE);
    }

    // --- Admin Desk Operations Tests ---

    @Test
    @DisplayName("checkOutReservation: succeeds within valid check-out window (start - 15m to start + 30m)")
    void checkOutReservationSuccessWithinWindow() {
        // Fixed clock is 2026-11-02T10:00:00 (Bucharest time).
        // Slot from 10:00 to 12:00 -> window is 09:45 to 10:30.
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 101L);

        when(reservationRepository.findById(101L)).thenReturn(Optional.of(r));
        when(currentUser.id()).thenReturn(1L);

        ReservationResponse response = reservationService.checkOutReservation(101L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_OUT);
        assertThat(response.checkedOutAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 10, 0));
        verify(auditService).record(eq(1L), eq("CHECK_OUT_RESERVATION"), eq("Reservation"), eq(101L), any());
    }

    @Test
    @DisplayName("checkOutReservation: rejects if current time is before window (earlier than start - 15m)")
    void checkOutReservationRejectsBeforeWindow() {
        // Clock is 10:00:00. Slot starts at 10:30:00 -> window starts at 10:15:00.
        // Current time (10:00) is too early.
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 30),
                LocalDateTime.of(2026, 11, 2, 12, 30),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 102L);

        when(reservationRepository.findById(102L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.checkOutReservation(102L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(
                        "Check-out is only allowed between 15 minutes before slot start and 30 minutes after slot start")
                .extracting("code")
                .isEqualTo("CHECKOUT_WINDOW_CLOSED");
    }

    @Test
    @DisplayName("checkOutReservation: rejects if current time is after window (later than start + 30m)")
    void checkOutReservationRejectsAfterWindow() {
        // Clock is 10:00:00. Slot started at 09:00:00 -> window ended at 09:30:00.
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 103L);

        when(reservationRepository.findById(103L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.checkOutReservation(103L))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code")
                .isEqualTo("CHECKOUT_WINDOW_CLOSED");
    }

    @Test
    @DisplayName("checkOutReservation: throws NotFoundException when reservation does not exist")
    void checkOutReservationNotFound() {
        when(reservationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.checkOutReservation(999L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("RESERVATION_NOT_FOUND");
    }

    @Test
    @DisplayName("checkOutReservation: throws InvalidStateTransitionException if reservation is not CONFIRMED")
    void checkOutReservationInvalidState() {
        // Clock is 10:00:00. Slot 10:00 - 12:00.
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 104L);
        r.cancel(LocalDateTime.of(2026, 11, 1, 12, 0), "Cancelled");

        when(reservationRepository.findById(104L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.checkOutReservation(104L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .extracting("code")
                .isEqualTo("INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("returnReservation: succeeds for CHECKED_OUT reservation")
    void returnReservationSuccessFromCheckedOut() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 201L);
        r.checkOut(LocalDateTime.of(2026, 11, 2, 8, 5));

        when(reservationRepository.findById(201L)).thenReturn(Optional.of(r));
        when(currentUser.id()).thenReturn(1L);

        ReservationResponse response = reservationService.returnReservation(201L);

        assertThat(response.status()).isEqualTo(ReservationStatus.RETURNED);
        assertThat(response.returnedAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 10, 0));
        verify(auditService).record(eq(1L), eq("RETURN_RESERVATION"), eq("Reservation"), eq(201L), any());
    }

    @Test
    @DisplayName("returnReservation: succeeds for OVERDUE reservation")
    void returnReservationSuccessFromOverdue() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 202L);
        r.checkOut(LocalDateTime.of(2026, 11, 2, 8, 5));
        r.markOverdue();

        when(reservationRepository.findById(202L)).thenReturn(Optional.of(r));
        when(currentUser.id()).thenReturn(1L);

        ReservationResponse response = reservationService.returnReservation(202L);

        assertThat(response.status()).isEqualTo(ReservationStatus.RETURNED);
    }

    @Test
    @DisplayName("returnReservation: throws InvalidStateTransitionException if reservation is CONFIRMED")
    void returnReservationRejectsConfirmed() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 203L);

        when(reservationRepository.findById(203L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.returnReservation(203L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .extracting("code")
                .isEqualTo("INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("getAdminReservations: queries repository using specifications and returns PageResponse")
    void getAdminReservationsSuccess() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 301L);

        when(reservationRepository.findAll(
                        any(org.springframework.data.jpa.domain.Specification.class),
                        any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 20), 1));

        PageResponse<ReservationResponse> page = reservationService.getAdminReservations(
                ReservationStatus.CONFIRMED, USER_ID, ASSET_ID, java.time.LocalDate.of(2026, 11, 2), 0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).id()).isEqualTo(301L);
        assertThat(page.totalElements()).isEqualTo(1);
    }
}

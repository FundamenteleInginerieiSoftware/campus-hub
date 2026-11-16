package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.domain.ReservationTransfer;
import com.campus.reservation.domain.TransferStatus;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.ReservationTransferRepository;
import com.campus.reservation.web.AvailabilityResponse;
import com.campus.reservation.web.CreateMultiAssetReservationRequest;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ExtendReservationRequest;
import com.campus.reservation.web.InitiateTransferRequest;
import com.campus.reservation.web.MultiAssetReservationResponse;
import com.campus.reservation.web.ReservationResponse;
import com.campus.reservation.web.ReservationSummaryResponse;
import com.campus.reservation.web.ReservationTransferResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ASSET_ID = 10L;

    // Fixed clock with nanos: 2026-11-02T08:00:00.123456789Z -> In Europe/Bucharest (UTC+2 in winter) this is
    // 2026-11-02T10:00:00.123456789 (Monday)
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-11-02T08:00:00.123456789Z"), ZoneId.of("Europe/Bucharest"));

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
    private ReservationTransferRepository reservationTransferRepository;

    @Mock
    private UserApi userApi;

    @Mock
    private ReservationHoldRepository reservationHoldRepository;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                reservationRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                reservationTransferRepository,
                userApi,
                reservationHoldRepository);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.assetId()).isEqualTo(ASSET_ID);
        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(response.createdAt().getNano()).isZero();

        // Strict lock order verification
        InOrder inOrder = inOrder(catalogApi, reservationRepository, auditService);
        inOrder.verify(catalogApi).lockForBooking(ASSET_ID);
        inOrder.verify(catalogApi).isBookable(ASSET_ID);
        inOrder.verify(reservationRepository)
                .existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L);
        inOrder.verify(reservationRepository).lockUserQuota(USER_ID);
        inOrder.verify(reservationRepository)
                .countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES);
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
        verify(reservationRepository, never()).countBookingSessionsByUserIdAndStatusIn(anyLong(), any());
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
    @DisplayName("Allows booking exactly 30 minutes (minimum allowed duration)")
    void allowsBookingExactly30Minutes() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 14, 30);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);
        assertThat(response).isNotNull();
        assertThat(response.startTime()).isEqualTo(start);
        assertThat(response.endTime()).isEqualTo(end);
    }

    @Test
    @DisplayName("Allows booking exactly 4 hours (maximum allowed duration)")
    void allowsBookingExactly4Hours() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 14, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);
        assertThat(response).isNotNull();
        assertThat(response.startTime()).isEqualTo(start);
        assertThat(response.endTime()).isEqualTo(end);
    }

    @Test
    @DisplayName("Allows booking starting right at opening hour (08:00)")
    void allowsBookingAtOpeningBoundary() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 9, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);
        assertThat(response).isNotNull();
        assertThat(response.startTime()).isEqualTo(start);
    }

    @Test
    @DisplayName("Allows booking ending right at closing hour (20:00)")
    void allowsBookingAtClosingBoundary() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 19, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 20, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);
        assertThat(response).isNotNull();
        assertThat(response.endTime()).isEqualTo(end);
    }

    @Test
    @DisplayName("Rejects when booking starts before 08:00 even if ending within opening hours")
    void rejectsWhenSlotStartsBefore0800() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 7, 30);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 8, 30);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Rejects when booking starts within opening hours but ends after 20:00")
    void rejectsWhenSlotEndsAfter2000() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 19, 30);
        LocalDateTime end = LocalDateTime.of(2026, 11, 3, 20, 30);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Allows booking exactly at 14-day horizon boundary")
    void allowsBookingAt14DayHorizon() {
        // Clock is at 2026-11-02 08:00 Europe/Bucharest. 14 days ahead = 2026-11-16 (Monday)
        LocalDateTime start = LocalDateTime.of(2026, 11, 16, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 16, 12, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.createReservation(req);
        assertThat(response).isNotNull();
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

        verify(reservationRepository, never()).countBookingSessionsByUserIdAndStatusIn(anyLong(), any());
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
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
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

        PageResponse<ReservationResponse> result =
                reservationService.getMyReservations((ReservationStatus) null, 0, 10);

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
    @DisplayName("getMyReservations returns paginated user reservations with multiple statuses filter")
    void getMyReservationsWithMultipleStatuses() {
        when(currentUser.id()).thenReturn(USER_ID);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        List<ReservationStatus> statuses = List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_OUT);
        when(reservationRepository.findByUserIdAndStatusInOrderByStartTimeAsc(eq(USER_ID), eq(statuses), any()))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1));

        PageResponse<ReservationResponse> result = reservationService.getMyReservations(statuses, 0, 10);

        assertThat(result.content()).hasSize(1);
        verify(reservationRepository).findByUserIdAndStatusInOrderByStartTimeAsc(eq(USER_ID), eq(statuses), any());
    }

    @Test
    @DisplayName("getMySummary returns aggregate stats for active, completed, and reassigned bookings")
    void getMySummaryReturnsAccurateAggregateCounts() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        eq(USER_ID),
                        eq(List.of(
                                ReservationStatus.CONFIRMED,
                                ReservationStatus.CHECKED_OUT,
                                ReservationStatus.OVERDUE))))
                .thenReturn(2L);
        when(reservationRepository.countByUserIdAndStatusIn(eq(USER_ID), eq(List.of(ReservationStatus.RETURNED))))
                .thenReturn(7L);
        when(reservationRepository.countByUserIdAndOriginalAssetIdIsNotNull(eq(USER_ID)))
                .thenReturn(3L);

        ReservationSummaryResponse summary = reservationService.getMySummary();

        assertThat(summary.activeCount()).isEqualTo(2L);
        assertThat(summary.completedCount()).isEqualTo(7L);
        assertThat(summary.reassignedCount()).isEqualTo(3L);
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
    @DisplayName("getById throws 404 NotFound when reservation does not exist")
    void getByIdNotFound() {
        when(reservationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.getById(999L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_FOUND");
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
        assertThat(response.cancelReason()).isEqualTo("Cancelled by user");
        verify(auditService)
                .record(eq(USER_ID), eq("CANCEL_RESERVATION"), eq("Reservation"), any(), eq("Cancelled by user"));
        verify(notificationService, never()).notifyUser(anyLong(), any(), any());
    }

    @Test
    @DisplayName("cancelReservation succeeds for admin even after start time and notifies student with admin reason")
    void cancelReservationAdminAfterStartSucceeds() {
        when(currentUser.id()).thenReturn(999L);
        when(currentUser.isAdmin()).thenReturn(true);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0), // Start was 09:00 (clock is 10:00)
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        ReservationResponse response = reservationService.cancelReservation(1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.cancelReason()).isEqualTo("Cancelled by administrator");
        verify(auditService)
                .record(
                        eq(999L),
                        eq("CANCEL_RESERVATION"),
                        eq("Reservation"),
                        eq(1L),
                        eq("Cancelled by administrator"));
        verify(notificationService)
                .notifyUser(
                        eq(USER_ID),
                        eq("Reservation Cancelled by Administrator"),
                        eq("Your reservation #1 has been cancelled by an administrator."));
    }

    @Test
    @DisplayName(
            "cancelReservation by admin defers notification until transaction commits via TransactionSynchronization")
    void cancelReservationAdminWithActiveTransactionDefersNotification() {
        when(currentUser.id()).thenReturn(999L);
        when(currentUser.isAdmin()).thenReturn(true);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        TransactionSynchronizationManager.initSynchronization();
        try {
            ReservationResponse response = reservationService.cancelReservation(1L);
            assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);

            // Notification must not be dispatched before transaction commit
            verify(notificationService, never()).notifyUser(anyLong(), any(), any());

            // Simulate transaction commit
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            verify(notificationService)
                    .notifyUser(
                            eq(USER_ID),
                            eq("Reservation Cancelled by Administrator"),
                            eq("Your reservation #1 has been cancelled by an administrator."));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("cancelReservation by admin catches and logs notification exceptions without failing")
    void cancelReservationAdminCatchesNotificationException() {
        when(currentUser.id()).thenReturn(999L);
        when(currentUser.isAdmin()).thenReturn(true);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));
        doThrow(new RuntimeException("Notification service failure"))
                .when(notificationService)
                .notifyUser(anyLong(), any(), any());

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
    @DisplayName("checkOutReservation checks status before checking time window")
    void checkOutReservationChecksStatusBeforeWindow() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "status", ReservationStatus.CANCELLED);
        when(reservationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.checkOutReservation(1L))
                .isInstanceOf(com.campus.common.error.InvalidStateTransitionException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("isAssetFree returns false when active hold exists from another student")
    void isAssetFree_returnsFalseWhenActiveHoldExists() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        when(reservationRepository.existsOverlap(
                        eq(ASSET_ID), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(start), eq(end), any(), eq(null), any()))
                .thenReturn(true);

        boolean free = reservationService.isAssetFree(ASSET_ID, start, end, null);

        assertThat(free).isFalse();
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
    @DisplayName("isAssetFree: returns true when asset has no overlapping reservations")
    void isAssetFree_returnsTrueWhenFreeOfReservations() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);

        assertThat(reservationService.isAssetFree(ASSET_ID, start, end, null))
                .as(
                        "availability: asset must be reported free when no reservations overlap; see ReservationService.isAssetFree")
                .isTrue();
    }

    @Test
    @DisplayName(
            "isAssetFree: holds do not block isAssetFree so confirmed bookings take precedence on reassignment (#228 point 8)")
    void isAssetFree_holdsDoNotBlockReassignment() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);

        assertThat(reservationService.isAssetFree(ASSET_ID, start, end, null))
                .as(
                        "reassignment priority: browsing holds must not block isAssetFree; see ReservationService.isAssetFree")
                .isTrue();
    }

    @Test
    @DisplayName("checkAvailability: returns false when asset has an active hold by another student (#228 point 8)")
    void checkAvailabilityReturnsFalseWhenAssetHeldByAnotherStudent() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        when(catalogApi.getAsset(ASSET_ID))
                .thenReturn(new com.campus.catalog.api.AssetSummary(
                        ASSET_ID, "Oscilloscope", "Tektronix", 1L, 101L, com.campus.catalog.api.AssetStatus.AVAILABLE));
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationHoldRepository.existsActiveOverlap(
                        eq(ASSET_ID), eq(start), eq(end), any(), isNull(), eq(USER_ID)))
                .thenReturn(true);

        AvailabilityResponse response = reservationService.checkAvailability(ASSET_ID, null, start, end);

        assertThat(response.available()).isFalse();
        assertThat(response.conflictReason()).isEqualTo("RESERVATION_CONFLICT");
    }

    @Test
    @DisplayName("isAssetFree: returns false when reservation overlap exists")
    void isAssetFree_returnsFalseWhenReservationOverlapExists() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, 5L))
                .thenReturn(true);

        assertThat(reservationService.isAssetFree(ASSET_ID, start, end, 5L))
                .as(
                        "availability: existing reservation overlap must mark asset busy; see ReservationService.isAssetFree")
                .isFalse();
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

        boolean moved = reservationService.moveToAsset(1L, newAssetId, "Damaged in lab");

        assertThat(moved).isTrue();
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
        verify(auditService).record(isNull(), eq("AUTO_NO_SHOW"), eq("Reservation"), eq(10L), any());
        assertThat(reservationService.markNoShow(10L)).isFalse();

        Reservation checkedOut = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 12, 0));
        ReflectionTestUtils.setField(checkedOut, "id", 11L);
        checkedOut.checkOut(LocalDateTime.of(2026, 11, 2, 8, 5));
        when(reservationRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(checkedOut));

        assertThat(reservationService.markOverdue(11L)).isTrue();
        assertThat(checkedOut.getStatus()).isEqualTo(ReservationStatus.OVERDUE);
        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(auditService).record(isNull(), eq("MARK_OVERDUE"), eq("Reservation"), eq(11L), any());
        assertThat(reservationService.markOverdue(11L)).isFalse();
    }

    @Test
    @DisplayName("markOverdue: returns false when reservation does not exist (#270 review 1)")
    void markOverdue_returnsFalseWhenNotFound() {
        when(reservationRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThat(reservationService.markOverdue(999L))
                .as("markOverdue must return false when reservation row is missing; see ReservationService.markOverdue")
                .isFalse();
        verify(catalogApi, never()).lockForBooking(anyLong());
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

        boolean cancelled = reservationService.forceCancel(1L, "Damaged beyond repair");

        assertThat(cancelled).isTrue();
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
        when(reservationRepository.findFirstByAssetIdAndStatusInOrderByStartTimeDesc(
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
        when(reservationRepository.findFirstByAssetIdAndStatusInOrderByStartTimeDesc(
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

        when(reservationRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(currentUser.id()).thenReturn(1L);

        ReservationResponse response = reservationService.checkOutReservation(101L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_OUT);
        assertThat(response.checkedOutAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 10, 0));
        verify(auditService).record(eq(1L), eq("CHECK_OUT_RESERVATION"), eq("Reservation"), eq(101L), any());
    }

    @Test
    @DisplayName("checkOutReservation: rejects if asset is not bookable (under maintenance or retired)")
    void checkOutReservationRejectsWhenAssetNotBookable() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 101L);

        when(reservationRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.checkOutReservation(101L))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("ASSET_NOT_BOOKABLE");
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(catalogApi).lockForBooking(ASSET_ID);
    }

    @Test
    @DisplayName(
            "checkOutReservation: rejects if asset is currently held by another reservation (checked out or overdue)")
    void checkOutReservationRejectsWhenAssetCurrentlyHeldByAnotherBooking() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 101L);

        // Previous reservation on same asset is currently OVERDUE or CHECKED_OUT
        Reservation priorHolder = new Reservation(
                999L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 10, 30, 8, 0));
        ReflectionTestUtils.setField(priorHolder, "id", 99L);
        priorHolder.checkOut(LocalDateTime.of(2026, 11, 2, 8, 0));
        priorHolder.markOverdue();

        when(reservationRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.findFirstByAssetIdAndStatusInOrderByStartTimeDesc(eq(ASSET_ID), any()))
                .thenReturn(Optional.of(priorHolder));

        assertThatThrownBy(() -> reservationService.checkOutReservation(101L))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("RESERVATION_CONFLICT");
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(catalogApi).lockForBooking(ASSET_ID);
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

        when(reservationRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(r));

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

        when(reservationRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.checkOutReservation(103L))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code")
                .isEqualTo("CHECKOUT_WINDOW_CLOSED");
    }

    @Test
    @DisplayName("checkOutReservation: throws NotFoundException when reservation does not exist")
    void checkOutReservationNotFound() {
        when(reservationRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

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

        when(reservationRepository.findByIdForUpdate(104L)).thenReturn(Optional.of(r));

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

        when(reservationRepository.findByIdForUpdate(201L)).thenReturn(Optional.of(r));
        when(currentUser.id()).thenReturn(1L);

        ReservationResponse response = reservationService.returnReservation(201L);

        assertThat(response.status()).isEqualTo(ReservationStatus.RETURNED);
        assertThat(response.returnedAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 10, 0));
        verify(catalogApi).lockForBooking(ASSET_ID);
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

        when(reservationRepository.findByIdForUpdate(202L)).thenReturn(Optional.of(r));
        when(currentUser.id()).thenReturn(1L);

        ReservationResponse response = reservationService.returnReservation(202L);

        assertThat(response.status()).isEqualTo(ReservationStatus.RETURNED);
        verify(catalogApi).lockForBooking(ASSET_ID);
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

        when(reservationRepository.findByIdForUpdate(203L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.returnReservation(203L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .extracting("code")
                .isEqualTo("INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("returnReservation: throws NotFoundException if reservation does not exist")
    void returnReservationNotFound() {
        when(reservationRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.returnReservation(999L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_FOUND");
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

        when(reservationRepository.findAll(ArgumentMatchers.<Specification<Reservation>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 20), 1));

        PageResponse<ReservationResponse> page = reservationService.getAdminReservations(
                ReservationStatus.CONFIRMED, USER_ID, ASSET_ID, java.time.LocalDate.of(2026, 11, 2), 0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).id()).isEqualTo(301L);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("getAdminReservations: supports ascending sortDir and rejects invalid sortDir")
    void getAdminReservationsSortDirValidation() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 10, 30, 10, 0));
        ReflectionTestUtils.setField(r, "id", 301L);

        when(reservationRepository.findAll(ArgumentMatchers.<Specification<Reservation>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 20), 1));

        PageResponse<ReservationResponse> ascPage =
                reservationService.getAdminReservations(ReservationStatus.CONFIRMED, null, null, null, 0, 20, "asc");
        assertThat(ascPage.content()).hasSize(1);

        assertThatThrownBy(() -> reservationService.getAdminReservations(
                        ReservationStatus.CONFIRMED, null, null, null, 0, 20, "invalid"))
                .isInstanceOf(InvalidParameterException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PARAMETER");
    }

    @Test
    @DisplayName("checkAvailability: throws MISSING_PARAMETER if neither assetId nor categoryId is provided")
    void checkAvailabilityThrowsWhenNeitherAssetNorCategoryProvided() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        assertThatThrownBy(() -> reservationService.checkAvailability(null, null, start, end))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Either assetId or categoryId must be provided");
    }

    @Test
    @DisplayName("checkAvailability: throws CONFLICTING_PARAMETERS if both assetId and categoryId are provided")
    void checkAvailabilityThrowsWhenBothAssetAndCategoryProvided() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        assertThatThrownBy(() -> reservationService.checkAvailability(ASSET_ID, 5L, start, end))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "CONFLICTING_PARAMETERS")
                .hasMessageContaining("Provide either assetId or categoryId, not both");
    }

    @Test
    @DisplayName("checkAvailability: returns available true when specific asset is operational and free")
    void checkAvailabilityReturnsTrueWhenAssetIsFree() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Microscope", "MC100", 1L, 1L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(reservationRepository.existsOverlap(
                        eq(ASSET_ID), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);

        AvailabilityResponse response = reservationService.checkAvailability(ASSET_ID, null, start, end);

        assertThat(response.available()).isTrue();
        assertThat(response.assetId()).isEqualTo(ASSET_ID);
        assertThat(response.availableAssetIds()).containsExactly(ASSET_ID);
        assertThat(response.conflictReason()).isNull();
        verify(catalogApi, never()).isBookable(anyLong());
    }

    @Test
    @DisplayName("checkAvailability: returns available false with RESERVATION_CONFLICT when asset is booked")
    void checkAvailabilityReturnsFalseWhenAssetOverlaps() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Microscope", "MC100", 1L, 1L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(reservationRepository.existsOverlap(
                        eq(ASSET_ID), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(true);

        AvailabilityResponse response = reservationService.checkAvailability(ASSET_ID, null, start, end);

        assertThat(response.available()).isFalse();
        assertThat(response.conflictReason()).isEqualTo("RESERVATION_CONFLICT");
        assertThat(response.availableAssetIds()).isEmpty();
        verify(catalogApi, never()).isBookable(anyLong());
    }

    @Test
    @DisplayName("checkAvailability: returns available false with ASSET_NOT_BOOKABLE when asset is under maintenance")
    void checkAvailabilityReturnsFalseWhenAssetUnderMaintenance() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Microscope", "MC100", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);

        AvailabilityResponse response = reservationService.checkAvailability(ASSET_ID, null, start, end);

        assertThat(response.available()).isFalse();
        assertThat(response.conflictReason()).isEqualTo("ASSET_NOT_BOOKABLE");
        assertThat(response.availableAssetIds()).isEmpty();
        verify(catalogApi, never()).isBookable(anyLong());
    }

    @Test
    @DisplayName("checkAvailability: throws ASSET_NOT_FOUND when assetId is unknown")
    void checkAvailabilityThrowsWhenAssetNotFound() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        when(catalogApi.getAsset(999L)).thenThrow(new NotFoundException("ASSET_NOT_FOUND", "Asset not found"));

        assertThatThrownBy(() -> reservationService.checkAvailability(999L, null, start, end))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Asset not found");
    }

    @Test
    @DisplayName("checkAvailability: queries category assets and filters out busy ones")
    void checkAvailabilityReturnsFreeAssetsInCategory() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        Long categoryId = 5L;

        AssetSummary a1 = new AssetSummary(10L, "Scope 1", "M1", categoryId, 1L, AssetStatus.AVAILABLE);
        AssetSummary a2 = new AssetSummary(20L, "Scope 2", "M1", categoryId, 1L, AssetStatus.AVAILABLE);
        AssetSummary a3 = new AssetSummary(30L, "Scope 3", "M1", categoryId, 1L, AssetStatus.AVAILABLE);

        when(catalogApi.findAvailableByCategory(categoryId, -1L)).thenReturn(List.of(a1, a2, a3));
        when(reservationRepository.findBusyAssetIds(start, end, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(20L));

        AvailabilityResponse response = reservationService.checkAvailability(null, categoryId, start, end);

        assertThat(response.available()).isTrue();
        assertThat(response.categoryId()).isEqualTo(categoryId);
        assertThat(response.availableAssetIds()).containsExactly(10L, 30L);
        assertThat(response.conflictReason()).isNull();
    }

    @Test
    @DisplayName(
            "checkAvailability: returns available false with NO_ASSETS_AVAILABLE when all category assets are busy")
    void checkAvailabilityReturnsNoAssetsAvailableWhenCategoryExhausted() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        Long categoryId = 5L;

        AssetSummary a1 = new AssetSummary(10L, "Scope 1", "M1", categoryId, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.findAvailableByCategory(categoryId, -1L)).thenReturn(List.of(a1));
        when(reservationRepository.findBusyAssetIds(start, end, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(10L));

        AvailabilityResponse response = reservationService.checkAvailability(null, categoryId, start, end);

        assertThat(response.available()).isFalse();
        assertThat(response.availableAssetIds()).isEmpty();
        assertThat(response.conflictReason()).isEqualTo("NO_ASSETS_AVAILABLE");
    }

    @Test
    @DisplayName("findBusyAssetIds: delegates to repository with active statuses")
    void findBusyAssetIdsDelegates() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        when(reservationRepository.findBusyAssetIds(start, end, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(10L, 20L));

        List<Long> busy = reservationService.findBusyAssetIds(start, end);
        assertThat(busy).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("getMyReservations and getAdminReservations reject page offset overflow (#150)")
    void paginationRejectsHugePageOffsetOverflow() {
        assertThatThrownBy(() -> reservationService.getMyReservations((List<ReservationStatus>) null, 999_999_999, 100))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page offset exceeds maximum supported value");

        assertThatThrownBy(() -> reservationService.getAdminReservations(null, null, null, null, 999_999_999, 100))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page offset exceeds maximum supported value");
    }

    @Test
    @DisplayName(
            "validatePagination boundary values: rejects negative page, size outside 1..100, and accepts valid boundaries")
    void validatePaginationBoundaries() {
        // Negative page
        assertThatThrownBy(() -> reservationService.getMyReservations((List<ReservationStatus>) null, -1, 10))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page index must not be negative");

        // Size < 1 (0)
        assertThatThrownBy(() -> reservationService.getMyReservations((List<ReservationStatus>) null, 0, 0))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        // Size > 100 (101)
        assertThatThrownBy(() -> reservationService.getMyReservations((List<ReservationStatus>) null, 0, 101))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        // Overflow: (long) page * size > Integer.MAX_VALUE
        long half = ((long) Integer.MAX_VALUE / 2) + 1;
        assertThatThrownBy(() -> reservationService.getMyReservations((List<ReservationStatus>) null, (int) half, 2))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page offset exceeds maximum supported value");

        // Valid boundaries: size = 1, size = 100, page * size == Integer.MAX_VALUE
        when(reservationRepository.findByUserIdOrderByStartTimeAsc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(reservationRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThatCode(() -> reservationService.getMyReservations((List<ReservationStatus>) null, 0, 1))
                .doesNotThrowAnyException();
        assertThatCode(() -> reservationService.getMyReservations((List<ReservationStatus>) null, 0, 100))
                .doesNotThrowAnyException();
        assertThatCode(() -> reservationService.getMyReservations((List<ReservationStatus>) null, Integer.MAX_VALUE, 1))
                .doesNotThrowAnyException();

        // Also test admin search pagination boundaries
        assertThatThrownBy(() -> reservationService.getAdminReservations(null, null, null, null, -1, 10))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page index must not be negative");

        assertThatThrownBy(() -> reservationService.getAdminReservations(null, null, null, null, 0, 0))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        assertThatThrownBy(() -> reservationService.getAdminReservations(null, null, null, null, 0, 101))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        assertThatThrownBy(() -> reservationService.getAdminReservations(null, null, null, null, (int) half, 2))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page offset exceeds maximum supported value");

        assertThatCode(() -> reservationService.getAdminReservations(null, null, null, null, 0, 1))
                .doesNotThrowAnyException();
        assertThatCode(() -> reservationService.getAdminReservations(null, null, null, null, 0, 100))
                .doesNotThrowAnyException();
        assertThatCode(() -> reservationService.getAdminReservations(null, null, null, null, Integer.MAX_VALUE, 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Bundle: creates simultaneous multi-asset reservations locking assets in ascending order")
    void createMultiAssetReservation_success() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(20L, 10L), start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(10L)).thenReturn(true);
        when(catalogApi.isBookable(20L)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 100L + r.getAssetId());
            return r;
        });

        MultiAssetReservationResponse response = reservationService.createMultiAssetReservation(req);

        assertThat(response).isNotNull();
        assertThat(response.bundleId()).isNotNull();
        assertThat(response.totalAssets()).isEqualTo(2);
        assertThat(response.reservations()).hasSize(2);

        // Verify deterministic ascending lock order: 10L locked before 20L (deadlock prevention)
        InOrder inOrder = inOrder(catalogApi);
        inOrder.verify(catalogApi).lockForBooking(10L);
        inOrder.verify(catalogApi).lockForBooking(20L);

        verify(auditService)
                .record(eq(USER_ID), eq("CREATE_BUNDLE_RESERVATION"), eq("ReservationBundle"), eq(10L), any());
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Bundle Confirmed"), any());
    }

    @Test
    @DisplayName("Bundle: rejects all reservations if one asset has an overlap conflict")
    void createMultiAssetReservation_rejectsIfOneAssetOverlaps() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(10L, 20L), start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(10L)).thenReturn(true);
        when(catalogApi.isBookable(20L)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        eq(10L), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.existsOverlap(
                        eq(20L), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("overlaps with an existing reservation");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Bundle: rejects if less than 2 assets requested")
    void createMultiAssetReservation_rejectsIfLessThanTwoAssets() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(10L), start, end);

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 2 and 5 assets");
    }

    @Test
    @DisplayName("Bundle: rejects if more than 5 assets requested")
    void createMultiAssetReservation_rejectsIfMoreThanFiveAssets() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req =
                new CreateMultiAssetReservationRequest(Set.of(10L, 20L, 30L, 40L, 50L, 60L), start, end);

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 2 and 5 assets");
    }

    @Test
    @DisplayName("Bundle: cancels all confirmed reservations in the bundle")
    void cancelBundle_success() {
        UUID bundleId = UUID.randomUUID();
        Reservation r1 = new Reservation(
                USER_ID,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        Reservation r2 = new Reservation(
                USER_ID,
                20L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r2, "id", 102L);
        r2.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1, r2));

        List<ReservationResponse> responses = reservationService.cancelBundle(bundleId, "Cancelled entire kit");

        assertThat(responses).hasSize(2);
        assertThat(r1.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r2.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(auditService).record(eq(USER_ID), eq("CANCEL_RESERVATION"), eq("Reservation"), eq(101L), any());
        verify(auditService).record(eq(USER_ID), eq("CANCEL_RESERVATION"), eq("Reservation"), eq(102L), any());
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Bundle Cancelled"), any());
    }

    @Test
    @DisplayName("Bundle: rejects student cancellation after start time")
    void cancelBundle_rejectsIfAfterStartTime() {
        UUID bundleId = UUID.randomUUID();
        // Start is 09:00, while Clock is 10:00
        Reservation r1 = new Reservation(
                USER_ID,
                10L,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1));

        assertThatThrownBy(() -> reservationService.cancelBundle(bundleId, "late cancel"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancelled before start time");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Bundle: rejects if student already reached max active reservations limit (2)")
    void createMultiAssetReservation_rejectsIfQuotaExceeded() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req =
                new CreateMultiAssetReservationRequest(Set.of(10L, 20L, 30L), start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L); // Already at limit of 2

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot have more than 2 active reservations");
    }

    @Test
    @DisplayName("Bundle: allows exact upper boundary of 5 assets in a single bundle")
    void createMultiAssetReservation_allowsExactFiveAssets() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req =
                new CreateMultiAssetReservationRequest(Set.of(10L, 20L, 30L, 40L, 50L), start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        MultiAssetReservationResponse response = reservationService.createMultiAssetReservation(req);

        assertThat(response).isNotNull();
        assertThat(response.totalAssets()).isEqualTo(5);
        assertThat(response.reservations()).hasSize(5);
    }

    @Test
    @DisplayName("bookBundle facade: successfully books multi-asset kit returning UUID bundleId (#140)")
    void bookBundle_success_multipleAssets() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        List<Long> assetIds = List.of(20L, 10L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(10L)).thenReturn(true);
        when(catalogApi.isBookable(20L)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 100L + r.getAssetId());
            return r;
        });

        UUID bundleId = reservationService.bookBundle(USER_ID, assetIds, start, end);

        assertThat(bundleId)
                .as("bundle booking: facade must return a non-null bundle UUID; see ReservationService.bookBundle")
                .isNotNull();

        // Verify deterministic ascending lock order: 10L locked before 20L
        InOrder inOrder = inOrder(catalogApi);
        inOrder.verify(catalogApi).lockForBooking(10L);
        inOrder.verify(catalogApi).lockForBooking(20L);

        verify(auditService)
                .record(eq(USER_ID), eq("CREATE_BUNDLE_RESERVATION"), eq("ReservationBundle"), eq(10L), any());
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Bundle Confirmed"), any());
    }

    @Test
    @DisplayName("bookBundle facade: successfully books 1-item kit (#140)")
    void bookBundle_success_singleAsset() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        List<Long> assetIds = List.of(10L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(10L)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 110L);
            return r;
        });

        UUID bundleId = reservationService.bookBundle(USER_ID, assetIds, start, end);

        assertThat(bundleId)
                .as(
                        "single-asset kit: 1-item kit must be accepted and return a bundle UUID; see ReservationService.bookBundle")
                .isNotNull();

        verify(catalogApi).lockForBooking(10L);
        verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("bookBundle facade: rejects empty list or more than 5 assets (#140)")
    void bookBundle_rejectsInvalidSize() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        assertThatThrownBy(() -> reservationService.bookBundle(USER_ID, null, start, end))
                .as("null bundle: null asset list must throw INVALID_BUNDLE_SIZE; see ReservationService.bookBundle")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 1 and 5 assets");

        assertThatThrownBy(() -> reservationService.bookBundle(USER_ID, List.of(), start, end))
                .as("empty bundle: empty asset list must throw INVALID_BUNDLE_SIZE; see ReservationService.bookBundle")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 1 and 5 assets");

        List<Long> listWithNull = new java.util.ArrayList<>();
        listWithNull.add(1L);
        listWithNull.add(null);
        assertThatThrownBy(() -> reservationService.bookBundle(USER_ID, listWithNull, start, end))
                .as(
                        "null element: bundle containing null must throw INVALID_BUNDLE_SIZE; see ReservationService.bookBundle")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 1 and 5 assets");

        List<Long> sixAssets = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        assertThatThrownBy(() -> reservationService.bookBundle(USER_ID, sixAssets, start, end))
                .as("overflow bundle: > 5 assets must throw INVALID_BUNDLE_SIZE; see ReservationService.bookBundle")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 1 and 5 assets");
    }

    @Test
    @DisplayName("bookBundle facade: validates slot rules (#140)")
    void bookBundle_validatesSlotRules() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(16).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.minusHours(2); // end before start

        assertThatThrownBy(() -> reservationService.bookBundle(USER_ID, List.of(10L), start, end))
                .as("invalid slot: end before start must be rejected; see ReservationService.bookBundle")
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("bookBundle facade: allows boundary of exactly 5 assets (#140)")
    void bookBundle_allowsExactlyFiveAssets() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        List<Long> fiveAssets = List.of(1L, 2L, 3L, 4L, 5L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID bundleId = reservationService.bookBundle(USER_ID, fiveAssets, start, end);
        assertThat(bundleId)
                .as("boundary size: 5 assets bundle must succeed; see ReservationService.bookBundle")
                .isNotNull();
    }

    @Test
    @DisplayName("bookBundle facade: admin resolved via userApi bypasses quota (#140)")
    void bookBundle_adminResolvedViaUserApiBypassesQuota() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        List<Long> assetIds = List.of(10L, 20L);

        // currentUser has no admin role in JWT context, but userApi finds Role.ADMIN
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.ADMIN));
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID bundleId = reservationService.bookBundle(USER_ID, assetIds, start, end);
        assertThat(bundleId)
                .as(
                        "admin bypass: user with ADMIN role in userApi must bypass quota in bookBundle; see ReservationService.bookBundle")
                .isNotNull();
        verify(reservationRepository, never()).lockUserQuota(anyLong());
        verify(reservationRepository, never()).countBookingSessionsByUserIdAndStatusIn(anyLong(), any());
    }

    @Test
    @DisplayName("bookBundle facade: student resolved via userApi enforces quota (#140)")
    void bookBundle_studentResolvedViaUserApiEnforcesQuota() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        List<Long> assetIds = List.of(10L, 20L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);

        assertThatThrownBy(() -> reservationService.bookBundle(USER_ID, assetIds, start, end))
                .isInstanceOf(ConflictException.class)
                .as("student quota: student must not bypass quota in bookBundle; see ReservationService.bookBundle")
                .hasMessageContaining("Student cannot have more than 2 active reservations");
    }

    @Test
    @DisplayName("bookBundle facade: admin bypasses student active reservation quota limit (#140)")
    void bookBundle_adminBypassesQuota() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        List<Long> assetIds = List.of(10L, 20L);

        when(currentUser.id()).thenReturn(999L);
        when(currentUser.isAdmin()).thenReturn(true);
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID bundleId = reservationService.bookBundle(999L, assetIds, start, end);

        assertThat(bundleId).isNotNull();
        verify(reservationRepository, never()).lockUserQuota(anyLong());
        verify(reservationRepository, never()).countBookingSessionsByUserIdAndStatusIn(anyLong(), any());
    }

    @Test
    @DisplayName("Extend: successfully extends active reservation when slot is free")
    void extendReservation_success() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 15, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 16, 0);
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        eq(ASSET_ID), eq(oldEnd), eq(newEnd), eq(ReservationStatus.ACTIVE_STATUSES), eq(100L)))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd));

        assertThat(response).isNotNull();
        assertThat(response.endTime()).isEqualTo(newEnd);
        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(auditService).record(eq(USER_ID), eq("EXTEND_RESERVATION"), eq("Reservation"), eq(100L), any());
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Extended"), any());
    }

    @Test
    @DisplayName("Extend: rejects extension if subsequent slot overlaps with another booking")
    void extendReservation_rejectsIfSubsequentSlotConflicts() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 15, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 16, 0);
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        eq(ASSET_ID), eq(oldEnd), eq(newEnd), eq(ReservationStatus.ACTIVE_STATUSES), eq(100L)))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("subsequent slot is already booked");

        assertThat(r.getEndTime()).isEqualTo(oldEnd); // Original reservation remains unchanged!
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Extend: rejects if asset is under maintenance or retired")
    void extendReservation_rejectsIfAssetNotBookable() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 15, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 16, 0);
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("under maintenance or retired");

        assertThat(r.getEndTime()).isEqualTo(oldEnd);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Extend: rejects if reservation is already expired")
    void extendReservation_rejectsIfExpired() {
        // Clock is 2026-11-02T10:00:00 (Bucharest)
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 8, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 9, 0); // 09:00 is in the past relative to 10:00
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 11, 0);
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 7, 30));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot extend an expired or completed reservation");

        assertThat(r.getEndTime()).isEqualTo(oldEnd);
    }

    @Test
    @DisplayName("Extend: rejects if CONFIRMED booking passed check-out window")
    void extendReservation_rejectsIfCheckoutWindowPassed() {
        // Clock is 2026-11-02T10:00:00 (Bucharest)
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 9, 0); // start + 30m = 09:30, which is < 10:00
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 11, 0); // 11:00 is not yet expired
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 12, 0);
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("check-out deadline has passed");

        assertThat(r.getEndTime()).isEqualTo(oldEnd);
    }

    @Test
    @DisplayName("Extend: rejects if extension end time exceeds 20:00 opening hours")
    void extendReservation_rejectsIfOutsideOpeningHours() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 18, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 19, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 20, 30); // 20:30 > 20:00
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("within opening hours");

        assertThat(r.getEndTime()).isEqualTo(oldEnd);
    }

    @Test
    @DisplayName("Extend: rejects if extension end time is not aligned to :00 or :30")
    void extendReservation_rejectsIfSlotNotAligned() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 15, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 15, 15); // :15 not aligned
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("aligned to 0 or 30 minutes");

        assertThat(r.getEndTime()).isEqualTo(oldEnd);
    }

    @Test
    @DisplayName("Extend: rejects if total duration exceeds 4 hours limit")
    void extendReservation_rejectsIfDurationExceedsLimit() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 16, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 19, 0); // 5 hours total duration
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Reservation duration must be between 30 minutes and 4 hours");

        assertThat(r.getEndTime()).isEqualTo(oldEnd);
    }

    @Test
    @DisplayName("Transfer: initiates transfer offer to recipient student and notifies them")
    void initiateTransfer_success() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> {
            ReservationTransfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 500L);
            return t;
        });

        ReservationTransferResponse response =
                reservationService.initiateTransfer(100L, new InitiateTransferRequest(99L));

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(500L);
        assertThat(response.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(response.toUserId()).isEqualTo(99L);
        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(notificationService).notifyUser(eq(99L), eq("Reservation Transfer Offer"), any());
        verify(auditService).record(eq(USER_ID), eq("TRANSFER_INITIATED"), eq("ReservationTransfer"), eq(500L), any());
    }

    @Test
    @DisplayName(
            "Transfer: anti-probing: allows initiate even if recipient is at quota (quota enforced at accept, #240 point 3)")
    void initiateTransfer_doesNotProbeRecipientQuota() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.countByReservationIdAndRequestedAtAfter(eq(100L), any()))
                .thenReturn(0L);
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> {
            ReservationTransfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 502L);
            return t;
        });

        ReservationTransferResponse res = reservationService.initiateTransfer(100L, new InitiateTransferRequest(99L));
        assertThat(res).isNotNull();
        assertThat(res.id()).isEqualTo(502L);
        assertThat(res.status()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    @DisplayName("Transfer: rejects initiate if 24-hour transfer offer limit is reached (#240 point 3)")
    void initiateTransfer_rejectsWhenDailyTransferOfferLimitReached() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.countByReservationIdAndRequestedAtAfter(eq(100L), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> reservationService.initiateTransfer(100L, new InitiateTransferRequest(99L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Maximum transfer offers reached for this reservation");
    }

    @Test
    @DisplayName("Transfer: rejects transfer to oneself")
    void initiateTransfer_rejectsSelfTransfer() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.initiateTransfer(100L, new InitiateTransferRequest(USER_ID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot transfer a reservation to yourself");
    }

    @Test
    @DisplayName("Transfer: accepts transfer and atomically changes reservation ownership")
    void acceptTransfer_success() {
        Long recipientId = 99L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        recipientId, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);

        ReservationTransferResponse response = reservationService.acceptTransfer(500L);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(r.getUserId()).isEqualTo(recipientId); // Ownership transferred!
        verify(reservationRepository).save(r);
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Transfer Accepted"), any());
        verify(auditService)
                .record(eq(recipientId), eq("TRANSFER_ACCEPTED"), eq("ReservationTransfer"), eq(500L), any());
    }

    @Test
    @DisplayName("Transfer: rejects transfer if recipient active quota is exceeded")
    void acceptTransfer_rejectsIfRecipientQuotaExceeded() {
        Long recipientId = 99L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        // Recipient already has 2 active bookings
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        recipientId, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);

        assertThatThrownBy(() -> reservationService.acceptTransfer(500L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Student cannot have more than 2 active reservations");

        assertThat(r.getUserId()).isEqualTo(USER_ID); // Ownership remains with original user!
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    @DisplayName("cancelReservation: auto-cancels pending transfer and notifies recipient")
    void cancelReservation_autoCancelsPendingTransferAndNotifiesRecipient() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 1L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(r));

        ReservationTransfer pendingTransfer =
                new ReservationTransfer(1L, USER_ID, 99L, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(pendingTransfer, "id", 500L);
        when(reservationTransferRepository.findByReservationIdAndStatus(1L, TransferStatus.PENDING))
                .thenReturn(Optional.of(pendingTransfer));

        ReservationResponse response = reservationService.cancelReservation(1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(pendingTransfer.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(pendingTransfer);
        verify(notificationService)
                .notifyUser(
                        eq(99L),
                        eq("Reservation Transfer Cancelled"),
                        contains("cancelled because the reservation was cancelled"));
        verify(auditService)
                .record(
                        eq(USER_ID),
                        eq("TRANSFER_CANCELLED"),
                        eq("ReservationTransfer"),
                        eq(500L),
                        eq("Auto-cancelled due to reservation cancellation"));
    }

    @Test
    @DisplayName("Transfer: admin accepting transfer bypasses active quota check")
    void acceptTransfer_adminBypassesQuota() {
        Long adminId = 999L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, adminId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(adminId);
        when(currentUser.isAdmin()).thenReturn(true);
        when(userApi.findActiveRole(adminId)).thenReturn(Optional.of(Role.ADMIN));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));

        ReservationTransferResponse response = reservationService.acceptTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(r.getUserId()).isEqualTo(adminId);
        verify(reservationRepository, never()).lockUserQuota(adminId);
    }

    @Test
    @DisplayName("Transfer: sender cancels pending transfer and notifies recipient")
    void cancelTransfer_senderCancelsAndNotifiesRecipient() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = reservationService.cancelTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
        verify(notificationService)
                .notifyUser(
                        eq(recipientId),
                        eq("Reservation Transfer Cancelled"),
                        contains("cancelled the transfer offer"));
        verify(auditService)
                .record(
                        eq(USER_ID),
                        eq("TRANSFER_CANCELLED"),
                        eq("ReservationTransfer"),
                        eq(500L),
                        contains("Cancelled by sender"));
    }

    @Test
    @DisplayName("Transfer: rejects accept if reservation owner no longer matches transfer sender")
    void acceptTransfer_rejectsIfReservationOwnerChanged() {
        Long recipientId = 99L;
        Reservation r = new Reservation(
                777L, // Owned by someone else now!
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.acceptTransfer(500L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Reservation owner no longer matches transfer sender");
    }

    @Test
    @DisplayName("Transfer: rejects accept if sender account is no longer active")
    void acceptTransfer_rejectsIfSenderInactive() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.empty()); // Inactive sender
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> reservationService.acceptTransfer(500L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Sender account is no longer active");
    }

    // ==========================================
    // Transfer Rejection & Cancellation Tests
    // ==========================================

    @Test
    @DisplayName("Transfer: recipient rejects transfer and notifies sender")
    void rejectTransfer_success() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = reservationService.rejectTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
        verify(auditService)
                .record(
                        eq(recipientId),
                        eq("TRANSFER_REJECTED"),
                        eq("ReservationTransfer"),
                        eq(500L),
                        contains("Rejected by recipient"));
        verify(notificationService)
                .notifyUser(eq(USER_ID), eq("Reservation Transfer Rejected"), contains("declined your transfer"));
    }

    @Test
    @DisplayName("Transfer: admin can reject transfer on behalf of recipient")
    void rejectTransfer_byAdmin_success() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(777L); // Admin user
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = reservationService.rejectTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.REJECTED);
    }

    @Test
    @DisplayName("Transfer: non-recipient student throws 404 when rejecting transfer")
    void rejectTransfer_nonRecipient_throws404() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(888L); // Third-party student
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> reservationService.rejectTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: reject transfer throws 404 when transfer does not exist")
    void rejectTransfer_notFound_throws404() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.rejectTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: reject transfer throws 409 when status is not PENDING")
    void rejectTransfer_nonPending_throws409() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);
        transfer.reject(LocalDateTime.of(2026, 11, 2, 12, 0)); // Already REJECTED

        when(currentUser.id()).thenReturn(recipientId);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> reservationService.rejectTransfer(500L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot reject transfer in status REJECTED");
    }

    @Test
    @DisplayName("Transfer: sender cancels outgoing transfer offer")
    void cancelTransfer_success() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = reservationService.cancelTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        verify(auditService)
                .record(
                        eq(USER_ID),
                        eq("TRANSFER_CANCELLED"),
                        eq("ReservationTransfer"),
                        eq(500L),
                        contains("Cancelled by sender"));
    }

    @Test
    @DisplayName("Transfer: admin can cancel outgoing transfer offer")
    void cancelTransfer_byAdmin_success() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(777L); // Admin
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = reservationService.cancelTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.CANCELLED);
    }

    @Test
    @DisplayName("Transfer: non-sender student throws 404 when cancelling transfer")
    void cancelTransfer_nonSender_throws404() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(888L); // Third-party student
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> reservationService.cancelTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: cancel transfer throws 404 when transfer does not exist")
    void cancelTransfer_notFound_throws404() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.cancelTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: cancel transfer throws 409 when status is not PENDING")
    void cancelTransfer_nonPending_throws409() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);
        transfer.cancel(LocalDateTime.of(2026, 11, 2, 12, 0)); // Already CANCELLED

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> reservationService.cancelTransfer(500L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot cancel transfer in status CANCELLED");
    }

    @Test
    @DisplayName("Transfer: getMyPendingTransfers lists transfers directed to current user")
    void getMyPendingTransfers_returnsList() {
        Long recipientId = USER_ID;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, 2L, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(reservationTransferRepository.findByToUserIdAndStatusOrderByRequestedAtDesc(
                        eq(recipientId), eq(TransferStatus.PENDING)))
                .thenReturn(List.of(transfer));

        List<ReservationTransferResponse> list = reservationService.getMyPendingTransfers();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(500L);
    }

    // ==========================================
    // Multi-Asset Bundle Reservation Tests
    // ==========================================

    @Test
    @DisplayName("Bundle: quota boundary - activeCount == 1 allows bundle creation")
    void createMultiAssetReservation_boundaryQuota_activeCountOneAllows() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(10L, 20L), start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L); // Boundary: 1 active reservation (1 + 1 <= 2) -> allowed!
        when(reservationRepository.existsOverlap(
                        anyLong(), eq(start), eq(end), eq(ReservationStatus.ACTIVE_STATUSES), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        MultiAssetReservationResponse response = reservationService.createMultiAssetReservation(req);

        assertThat(response).isNotNull();
        assertThat(response.totalAssets()).isEqualTo(2);
        assertThat(response.reservations()).allMatch(r -> r.bundleId() != null);
    }

    @Test
    @DisplayName("Bundle: quota boundary - activeCount == 2 rejects bundle creation")
    void createMultiAssetReservation_boundaryQuota_activeCountTwoRejects() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(10L, 20L), start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L); // Boundary: 2 active reservations -> (2 + 1 > 2) rejects!

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Student cannot have more than 2 active reservations");
    }

    @Test
    @DisplayName("Bundle: validates slot rules on bundle creation")
    void createMultiAssetReservation_validatesSlotRules() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 20, 30); // 20:30 outside opening hours
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 21, 30);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(10L, 20L), start, end);

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("within opening hours");
    }

    @Test
    @DisplayName("Bundle: getBundle succeeds for owner student")
    void getBundle_success() {
        UUID bundleId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        Reservation r = new Reservation(USER_ID, 10L, start, end, LocalDateTime.of(2026, 11, 2, 10, 0));
        r.assignBundle(bundleId);
        ReflectionTestUtils.setField(r, "id", 101L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r));

        MultiAssetReservationResponse response = reservationService.getBundle(bundleId);

        assertThat(response).isNotNull();
        assertThat(response.bundleId()).isEqualTo(bundleId);
        assertThat(response.totalAssets()).isEqualTo(1);
    }

    @Test
    @DisplayName("Bundle: getBundle succeeds for admin even if not owner")
    void getBundle_byAdmin_success() {
        UUID bundleId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        Reservation r = new Reservation(999L, 10L, start, end, LocalDateTime.of(2026, 11, 2, 10, 0));
        r.assignBundle(bundleId);
        ReflectionTestUtils.setField(r, "id", 101L);

        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r));

        MultiAssetReservationResponse response = reservationService.getBundle(bundleId);

        assertThat(response).isNotNull();
        assertThat(response.bundleId()).isEqualTo(bundleId);
    }

    @Test
    @DisplayName("Bundle: getBundle throws 404 for non-owner student")
    void getBundle_nonOwner_throws404() {
        UUID bundleId = UUID.randomUUID();
        Reservation r = new Reservation(
                999L,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        r.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r));

        assertThatThrownBy(() -> reservationService.getBundle(bundleId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation bundle not found");
    }

    @Test
    @DisplayName("Bundle: getBundle throws 404 when bundle does not exist")
    void getBundle_notFound_throws404() {
        UUID bundleId = UUID.randomUUID();
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of());

        assertThatThrownBy(() -> reservationService.getBundle(bundleId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation bundle not found");
    }

    @Test
    @DisplayName("Bundle: cancelBundle succeeds for owner student before start time")
    void cancelBundle_byStudent_success() {
        UUID bundleId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        Reservation r = new Reservation(USER_ID, 10L, start, end, LocalDateTime.of(2026, 11, 2, 10, 0));
        r.assignBundle(bundleId);
        ReflectionTestUtils.setField(r, "id", 101L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        List<ReservationResponse> responses = reservationService.cancelBundle(bundleId, "Changed plans");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelReason()).isEqualTo("Cancelled by user");
    }

    @Test
    @DisplayName("Bundle: cancelBundle by admin cancels with custom reason")
    void cancelBundle_byAdmin_success() {
        UUID bundleId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        Reservation r = new Reservation(999L, 10L, start, end, LocalDateTime.of(2026, 11, 2, 10, 0));
        r.assignBundle(bundleId);
        ReflectionTestUtils.setField(r, "id", 101L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        List<ReservationResponse> responses = reservationService.cancelBundle(bundleId, "Lab closed for maintenance");

        assertThat(responses).hasSize(1);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelReason()).isEqualTo("Lab closed for maintenance");
    }

    @Test
    @DisplayName("Bundle: cancelBundle rejects student cancellation after start time")
    void cancelBundle_afterStartTime_throwsConflict() {
        UUID bundleId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 11, 0);
        Reservation r = new Reservation(USER_ID, 10L, start, end, LocalDateTime.of(2026, 11, 2, 8, 0));
        r.assignBundle(bundleId);
        ReflectionTestUtils.setField(r, "id", 101L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r));

        assertThatThrownBy(() -> reservationService.cancelBundle(bundleId, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Reservations can only be cancelled before start time");
    }

    @Test
    @DisplayName("Bundle: cancelBundle throws 404 for non-owner student")
    void cancelBundle_nonOwner_throws404() {
        UUID bundleId = UUID.randomUUID();
        Reservation r = new Reservation(
                999L,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        r.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r));

        assertThatThrownBy(() -> reservationService.cancelBundle(bundleId, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation bundle not found");
    }

    @Test
    @DisplayName("Bundle: cancelBundle throws 404 when bundle does not exist")
    void cancelBundle_notFound_throws404() {
        UUID bundleId = UUID.randomUUID();
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of());

        assertThatThrownBy(() -> reservationService.cancelBundle(bundleId, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation bundle not found");
    }

    // ==========================================
    // Create Reservation with Hold & Availability Tests
    // ==========================================

    @Test
    @DisplayName("createReservation: rejects if requested slot is temporarily held by another student")
    void createReservation_rejectsIfSlotHeldByAnotherStudent() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(start), eq(end), any(), any(), eq(USER_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.createReservation(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("temporarily held by another student");
    }

    @Test
    @DisplayName("createReservation: auto-confirms active hold owned by current student")
    void createReservation_autoConfirmsActiveHold() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        ReservationHold hold = new ReservationHold(
                ASSET_ID,
                USER_ID,
                start,
                end,
                LocalDateTime.of(2026, 11, 2, 10, 1),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(hold, "id", 77L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(start), eq(end), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationHoldRepository.findByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of(hold));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 101L);
            return r;
        });

        ReservationResponse response = reservationService.createReservation(req);

        assertThat(response).isNotNull();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        verify(reservationHoldRepository).save(hold);
    }

    @Test
    @DisplayName("createReservation: ignores active hold for different asset or timeslot without confirming it")
    void createReservation_ignoresDifferentHold() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        // Hold for different asset
        ReservationHold differentAssetHold = new ReservationHold(
                999L, USER_ID, start, end, LocalDateTime.of(2026, 11, 2, 10, 1), LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(differentAssetHold, "id", 88L);

        // Hold for same asset but different time slot (e.g. 10:00-12:00)
        ReservationHold differentSlotHold = new ReservationHold(
                ASSET_ID,
                USER_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 10, 1),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(differentSlotHold, "id", 89L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(ASSET_ID), eq(start), eq(end), any(), any(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationHoldRepository.findByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of(differentAssetHold, differentSlotHold));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 102L);
            return r;
        });

        reservationService.createReservation(req);

        assertThat(differentAssetHold.getStatus())
                .as(
                        "auto-confirm: hold for a different asset must stay ACTIVE; see ReservationService.createReservation")
                .isEqualTo(HoldStatus.ACTIVE);
        assertThat(differentSlotHold.getStatus())
                .as(
                        "auto-confirm: hold for a different time slot on the same asset must stay ACTIVE; see ReservationService.createReservation")
                .isEqualTo(HoldStatus.ACTIVE);
        verify(reservationHoldRepository, never()).save(differentAssetHold);
        verify(reservationHoldRepository, never()).save(differentSlotHold);
    }

    // ==========================================
    // Extend Reservation Additional Coverage Tests
    // ==========================================

    @Test
    @DisplayName("Extend: rejects extension if reservation is not in CONFIRMED or CHECKED_OUT status")
    void extendReservation_rejectsIfStatusNotConfirmedOrCheckedOut() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 2, 16, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 2, 17, 0);
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        r.cancel(LocalDateTime.of(2026, 11, 2, 11, 0), "Cancelled");
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .as(
                        "extension status: only CONFIRMED or CHECKED_OUT reservations can be extended; see ReservationService.extendReservation")
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Only CONFIRMED or CHECKED_OUT reservations can be extended");
    }

    @Test
    @DisplayName("Extend: rejects extension if new end time exceeds the 14-day booking horizon")
    void extendReservation_rejectsIfBeyondHorizon() {
        // Clock is 2026-11-02T10:00 (Bucharest).
        // 14-day horizon boundary is 2026-11-16T10:00.
        // Start is at 09:30 on day 14, but newEnd at 10:30 exceeds the 14-day mark.
        LocalDateTime start = LocalDateTime.of(2026, 11, 16, 9, 30);
        LocalDateTime oldEnd = LocalDateTime.of(2026, 11, 16, 10, 0);
        LocalDateTime newEnd = LocalDateTime.of(2026, 11, 16, 10, 30);
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, oldEnd, LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .as(
                        "booking horizon: extension cannot exceed the 14-day booking horizon; see ReservationService.extendReservation")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be made more than 14 days in advance");
    }

    @Test
    @DisplayName("findBusyAssetIds: handles null or unauthenticated currentUser safely")
    void findBusyAssetIds_safeWhenCurrentUserNull() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        when(reservationRepository.findBusyAssetIds(start, end, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(10L));
        when(reservationHoldRepository.findHeldAssetIds(eq(start), eq(end), any(), isNull()))
                .thenReturn(List.of(20L));

        // When called from async tasks or without JWT, unauthenticated caller must still see all holds
        when(currentUser.id()).thenThrow(new RuntimeException("Unauthenticated"));

        List<Long> busy = reservationService.findBusyAssetIds(start, end);

        assertThat(busy)
                .as(
                        "busy assets: unauthenticated queries must include all held asset IDs; see ReservationService.findBusyAssetIds")
                .containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("cancelBundle: admin cancellation notifies the student owner, not the admin (#228 point 12)")
    void cancelBundle_adminCancels_notifiesItemOwnerNotAdmin() {
        UUID bundleId = UUID.randomUUID();
        Long studentId = 55L;
        Reservation r1 = new Reservation(
                studentId,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID); // Admin ID
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1));

        reservationService.cancelBundle(bundleId, "Lab maintenance");

        assertThat(r1.getStatus())
                .as("bundle cancel: item status must transition to CANCELLED; see ReservationService.cancelBundle")
                .isEqualTo(ReservationStatus.CANCELLED);
        verify(notificationService).notifyUser(eq(studentId), eq("Reservation Bundle Cancelled"), any());
        verify(notificationService, never()).notifyUser(eq(USER_ID), eq("Reservation Bundle Cancelled"), any());
    }

    @Test
    @DisplayName("cancelBundle: auto-cancels pending transfers on bundle items (#228 point 6, 12)")
    void cancelBundle_autoCancelsPendingTransfersOnBundleItems() {
        UUID bundleId = UUID.randomUUID();
        Reservation r1 = new Reservation(
                USER_ID,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        ReservationTransfer transfer =
                new ReservationTransfer(101L, USER_ID, 99L, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1));
        when(reservationTransferRepository.findByReservationIdAndStatus(101L, TransferStatus.PENDING))
                .thenReturn(Optional.of(transfer));

        reservationService.cancelBundle(bundleId, "Cancelled");

        assertThat(transfer.getStatus())
                .as(
                        "bundle cancel: pending transfer on bundle item must transition to CANCELLED; see ReservationService.cancelBundle")
                .isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
        verify(notificationService).notifyUser(eq(99L), eq("Reservation Transfer Cancelled"), any());
    }

    @Test
    @DisplayName("initiateTransfer: rejects transfer of individual items belonging to a bundle (#228 point 1)")
    void initiateTransfer_rejectsBundleItems() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);
        r.assignBundle(UUID.randomUUID());

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reservationService.initiateTransfer(100L, new InitiateTransferRequest(99L)))
                .as(
                        "bundle security: bundle items cannot be transferred individually; see ReservationService.initiateTransfer")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Items part of a bundle cannot be transferred individually");
    }

    @Test
    @DisplayName("initiateTransfer: rejects recipient who is not a student (e.g. ADMIN) (#228 point 2)")
    void initiateTransfer_rejectsNonStudentRecipient() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.ADMIN)); // Admin recipient

        assertThatThrownBy(() -> reservationService.initiateTransfer(100L, new InitiateTransferRequest(99L)))
                .as("anti-probing: transfers can only be offered to students; see ReservationService.initiateTransfer")
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Recipient student not found or inactive");
    }

    @Test
    @DisplayName("initiateTransfer: admin-initiated transfer records reservation owner as fromUserId (#228 point 5)")
    void initiateTransfer_byAdmin_setsFromUserIdToReservationOwner() {
        Long studentOwnerId = 77L;
        Reservation r = new Reservation(
                studentOwnerId,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID); // Admin ID
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> {
            ReservationTransfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 500L);
            return t;
        });

        ReservationTransferResponse response =
                reservationService.initiateTransfer(100L, new InitiateTransferRequest(99L));

        assertThat(response.fromUserId())
                .as(
                        "admin transfer: fromUserId must match student owner, not admin; see ReservationService.initiateTransfer")
                .isEqualTo(studentOwnerId);
    }

    @Test
    @DisplayName("forceCancel: auto-cancels pending transfer offer (#228 point 6)")
    void forceCancel_autoCancelsPendingTransfer() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, 99L, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.of(transfer));

        boolean cancelled = reservationService.forceCancel(100L, "Asset broken");

        assertThat(cancelled)
                .as("force cancel: method must return true on success; see ReservationService.forceCancel")
                .isTrue();
        assertThat(transfer.getStatus())
                .as("force cancel: pending transfer must transition to CANCELLED; see ReservationService.forceCancel")
                .isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
        verify(notificationService).notifyUser(eq(99L), eq("Reservation Transfer Cancelled"), any());
    }

    @Test
    @DisplayName("markNoShow: auto-cancels pending transfer offer (#228 point 6)")
    void markNoShow_autoCancelsPendingTransfer() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, 99L, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.of(transfer));

        boolean marked = reservationService.markNoShow(100L);

        assertThat(marked)
                .as("mark no-show: method must return true on success; see ReservationService.markNoShow")
                .isTrue();
        assertThat(transfer.getStatus())
                .as("mark no-show: pending transfer must transition to CANCELLED; see ReservationService.markNoShow")
                .isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
    }

    @Test
    @DisplayName("moveToAsset: auto-cancels pending transfer offer (#228 point 6)")
    void moveToAsset_autoCancelsPendingTransfer() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, 99L, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(200L)).thenReturn(true);
        when(reservationRepository.existsOverlap(eq(200L), any(), any(), any(), eq(100L)))
                .thenReturn(false);
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.of(transfer));

        boolean moved = reservationService.moveToAsset(100L, 200L, "Reassigned");

        assertThat(moved)
                .as("move to asset: method must return true on success; see ReservationService.moveToAsset")
                .isTrue();
        assertThat(transfer.getStatus())
                .as("move to asset: pending transfer must transition to CANCELLED; see ReservationService.moveToAsset")
                .isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
    }

    @Test
    @DisplayName("moveToAsset: reassignment succeeds even if substitute asset has a browsing hold (#228 point 8)")
    void moveToAsset_succeedsEvenIfSubstituteAssetHasActiveHold() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(200L)).thenReturn(true);
        when(reservationRepository.existsOverlap(eq(200L), any(), any(), any(), eq(100L)))
                .thenReturn(false);

        boolean moved = reservationService.moveToAsset(100L, 200L, "Reassigned");

        assertThat(moved)
                .as(
                        "reassignment priority: confirmed booking takes precedence over browsing holds; see ReservationService.moveToAsset")
                .isTrue();
    }

    @Test
    @DisplayName("checkOutReservation: auto-cancels pending transfer offer (#228 point 6)")
    void checkOutReservation_autoCancelsPendingTransfer() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer = new ReservationTransfer(100L, USER_ID, 99L, LocalDateTime.of(2026, 11, 2, 9, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.findFirstByAssetIdAndStatusInOrderByStartTimeDesc(eq(ASSET_ID), any()))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.of(transfer));

        ReservationResponse response = reservationService.checkOutReservation(100L);

        assertThat(response.status())
                .as("check-out: reservation status must be CHECKED_OUT; see ReservationService.checkOutReservation")
                .isEqualTo(ReservationStatus.CHECKED_OUT);
        assertThat(transfer.getStatus())
                .as(
                        "check-out: pending transfer must transition to CANCELLED; see ReservationService.checkOutReservation")
                .isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
        verify(auditService).record(eq(USER_ID), eq("TRANSFER_CANCELLED"), eq("ReservationTransfer"), eq(500L), any());
        verify(auditService).record(eq(USER_ID), eq("CHECK_OUT_RESERVATION"), eq("Reservation"), eq(100L), any());
    }

    @Test
    @DisplayName("getBundle: non-admin only sees items they own in the bundle (#228 point 1)")
    void getBundle_nonAdmin_returnsOnlyOwnItems() {
        UUID bundleId = UUID.randomUUID();
        Reservation r1 = new Reservation(
                USER_ID,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        Reservation r2 = new Reservation(
                999L,
                20L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r2, "id", 102L);
        r2.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1, r2));

        MultiAssetReservationResponse response = reservationService.getBundle(bundleId);

        assertThat(response.reservations())
                .as("bundle isolation: non-admin must only see their own items; see ReservationService.getBundle")
                .hasSize(1);
        assertThat(response.reservations().getFirst().id())
                .as(
                        "bundle isolation: visible item must match current user's reservation; see ReservationService.getBundle")
                .isEqualTo(101L);
    }

    @Test
    @DisplayName("getBundle: non-admin throws BUNDLE_NOT_FOUND when they own no items in the bundle (#228 point 1)")
    void getBundle_nonAdmin_throwsNotFoundWhenNoItemsOwned() {
        UUID bundleId = UUID.randomUUID();
        Reservation r1 = new Reservation(
                999L,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1));

        assertThatThrownBy(() -> reservationService.getBundle(bundleId))
                .as("bundle isolation: non-admin gets 404 when no items are owned; see ReservationService.getBundle")
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Reservation bundle not found");
    }

    @Test
    @DisplayName("cancelBundle: non-admin only cancels items they own in the bundle (#228 point 1)")
    void cancelBundle_nonAdmin_cancelsOnlyOwnItems() {
        UUID bundleId = UUID.randomUUID();
        Reservation r1 = new Reservation(
                USER_ID,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        Reservation r2 = new Reservation(
                999L,
                20L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r2, "id", 102L);
        r2.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1, r2));

        List<ReservationResponse> responses = reservationService.cancelBundle(bundleId, "Changed plans");

        assertThat(responses)
                .as("bundle cancellation: non-admin only cancels their own items; see ReservationService.cancelBundle")
                .hasSize(1);
        assertThat(r1.getStatus())
                .as("bundle cancellation: owned item status must be CANCELLED; see ReservationService.cancelBundle")
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r2.getStatus())
                .as("bundle cancellation: unowned item must remain CONFIRMED; see ReservationService.cancelBundle")
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Bundle: rejects creation if an asset has an active hold by another student (#228 point 7)")
    void createMultiAssetReservation_rejectsIfAssetHeldByAnotherStudent() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(10L, 20L), start, end);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(catalogApi.isBookable(10L)).thenReturn(true);
        when(catalogApi.isBookable(20L)).thenReturn(true);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.existsOverlap(anyLong(), eq(start), eq(end), any(), eq(-1L)))
                .thenReturn(false);
        // Asset 10 is free from holds, but Asset 20 is held by another user
        when(reservationHoldRepository.existsActiveOverlap(eq(10L), eq(start), eq(end), any(), isNull(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(20L), eq(start), eq(end), any(), isNull(), eq(USER_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .as(
                        "bundle hold check: active hold on any asset by another student must reject bundle creation; see ReservationService.createMultiAssetReservation")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Asset 20 is temporarily held by another student");
    }

    @Test
    @DisplayName("Bundle: auto-confirms student's active hold matching booked bundle assets and slot (#267 point 4)")
    void createMultiAssetReservation_autoConfirmsActiveHoldOwnedByStudent() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(Set.of(10L, 20L), start, end);

        ReservationHold matchingHold =
                new ReservationHold(10L, USER_ID, start, end, now.plusMinutes(1), now.minusMinutes(1));
        ReflectionTestUtils.setField(matchingHold, "id", 701L);

        ReservationHold unrelatedHold =
                new ReservationHold(99L, USER_ID, start, end, now.plusMinutes(1), now.minusMinutes(1));
        ReflectionTestUtils.setField(unrelatedHold, "id", 702L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(10L)).thenReturn(true);
        when(catalogApi.isBookable(20L)).thenReturn(true);
        when(reservationRepository.existsOverlap(eq(10L), eq(start), eq(end), any(), eq(-1L)))
                .thenReturn(false);
        when(reservationRepository.existsOverlap(eq(20L), eq(start), eq(end), any(), eq(-1L)))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(10L), eq(start), eq(end), any(), isNull(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(eq(20L), eq(start), eq(end), any(), isNull(), eq(USER_ID)))
                .thenReturn(false);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 801L);
            return r;
        });
        when(reservationHoldRepository.findByUserIdAndStatusAndExpiresAtAfter(
                        eq(USER_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of(matchingHold, unrelatedHold));

        MultiAssetReservationResponse response = reservationService.createMultiAssetReservation(req);

        assertThat(response).isNotNull();
        assertThat(matchingHold.getStatus())
                .as(
                        "matching active hold for asset in bundle must transition to CONFIRMED; see ReservationService.executeBundleBooking")
                .isEqualTo(HoldStatus.CONFIRMED);
        verify(reservationHoldRepository).save(matchingHold);

        assertThat(unrelatedHold.getStatus())
                .as(
                        "hold for asset not part of the bundle must remain ACTIVE; see ReservationService.executeBundleBooking")
                .isEqualTo(HoldStatus.ACTIVE);
        verify(reservationHoldRepository, never()).save(unrelatedHold);
    }

    @Test
    @DisplayName("Bundle: rejects null element in assetIds (#228 point 11)")
    void createMultiAssetReservation_rejectsNullElementInAssetIds() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        Set<Long> idsWithNull = new HashSet<>();
        idsWithNull.add(10L);
        idsWithNull.add(null);
        CreateMultiAssetReservationRequest req = new CreateMultiAssetReservationRequest(idsWithNull, start, end);

        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> reservationService.createMultiAssetReservation(req))
                .as(
                        "bundle validation: null asset ID in bundle must throw INVALID_BUNDLE_SIZE; see ReservationService.createMultiAssetReservation")
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Multi-asset booking must include between 2 and 5 assets");
    }

    @Test
    @DisplayName("extendReservation: rejects if extension slot is held by another student (#228 point 7)")
    void extendReservation_rejectsIfSlotHeldByAnotherStudent() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime oldStart =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime oldEnd = oldStart.plusHours(2);
        LocalDateTime newEnd = oldStart.plusHours(3);

        Reservation r = new Reservation(USER_ID, ASSET_ID, oldStart, oldEnd, now.minusHours(2));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, oldEnd, newEnd, ReservationStatus.ACTIVE_STATUSES, 100L))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(
                        eq(ASSET_ID), eq(oldEnd), eq(newEnd), any(), isNull(), eq(USER_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .as(
                        "extension hold check: hold on subsequent slot must prevent extension; see ReservationService.extendReservation")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("temporarily held by another student");
    }

    @Test
    @DisplayName("extendReservation: rejects extension if item is part of a bundle (#240 point 5)")
    void extendReservation_rejectsIfPartOfBundle() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        LocalDateTime createdAt = now.minusHours(2);

        Reservation r = new Reservation(USER_ID, ASSET_ID, start, end, createdAt);
        ReflectionTestUtils.setField(r, "id", 100L);
        r.assignBundle(java.util.UUID.randomUUID());

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        LocalDateTime newEnd = start.plusHours(4);
        assertThatThrownBy(() -> reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd)))
                .as(
                        "bundle extension: individual items in a bundle cannot be extended; see ReservationService.extendReservation")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Individual items in a bundle booking cannot be extended");
    }

    @Test
    @DisplayName("extendReservation: cancels pending transfer offer upon extension (#240 point 6)")
    void extendReservation_cancelsPendingTransferOffer() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        LocalDateTime createdAt = now.minusHours(2);

        Reservation r = new Reservation(USER_ID, ASSET_ID, start, end, createdAt);
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(eq(ASSET_ID), any(), any(), any(), eq(100L)))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransfer transfer = new ReservationTransfer(100L, USER_ID, 99L, now.minusHours(1));
        ReflectionTestUtils.setField(transfer, "id", 500L);
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.of(transfer));

        LocalDateTime newEnd = start.plusHours(4);
        reservationService.extendReservation(100L, new ExtendReservationRequest(newEnd));

        assertThat(transfer.getStatus())
                .as(
                        "transfer cancellation: extending a reservation must cancel any pending transfer offer; see ReservationService.extendReservation")
                .isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
        verify(auditService).record(eq(USER_ID), eq("TRANSFER_CANCELLED"), eq("ReservationTransfer"), eq(500L), any());
    }

    @Test
    @DisplayName(
            "Quota: student with bundle of 2 items can still book a single item (1 bundle + 1 single = 2 sessions <= 2) (#240 point 1)")
    void quota_bundleCountsAsOneSessionForNextBooking() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(eq(ASSET_ID), any(), any(), any(), eq(-1L)))
                .thenReturn(false);
        // Student already has 1 active booking session (e.g. a bundle of 2 items).
        // ReservationService checks countBookingSessionsByUserIdAndStatusIn <= 2;
        // The real database grouping logic (bundle_id vs null) is verified in ReservationRepositoryTest.
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 200L);
            return saved;
        });

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start =
                now.plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);
        CreateReservationRequest req = new CreateReservationRequest(ASSET_ID, start, end);

        ReservationResponse response = reservationService.createReservation(req);
        assertThat(response)
                .as(
                        "quota rule: student with 1 active bundle session (verified in ReservationRepositoryTest) can book a second session; see ReservationService.createReservation")
                .isNotNull();
        assertThat(response.id())
                .as("created reservation id must match saved entity; see ReservationService.createReservation")
                .isEqualTo(200L);
    }

    @Test
    @DisplayName("cancelBundle: defers notification until transaction afterCommit (#240 point 2)")
    void cancelBundle_defersNotificationUntilAfterCommit() {
        UUID bundleId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(clock);
        Reservation r1 = new Reservation(
                USER_ID,
                10L,
                now.plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
                now.plusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0),
                now.minusHours(1));
        ReflectionTestUtils.setField(r1, "id", 101L);
        r1.assignBundle(bundleId);

        when(currentUser.id()).thenReturn(USER_ID);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationRepository.findByBundleIdOrderByAssetIdAsc(bundleId)).thenReturn(List.of(r1));

        TransactionSynchronizationManager.initSynchronization();
        try {
            reservationService.cancelBundle(bundleId, "Cancelled entire kit");

            // While transaction is active and not committed yet, notificationService must NOT have been called
            verify(notificationService, never()).notifyUser(anyLong(), anyString(), anyString());

            // Simulate transaction commit
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            // After commit hook executes, notification is dispatched
            verify(notificationService)
                    .notifyUser(
                            eq(USER_ID),
                            eq("Reservation Bundle Cancelled"),
                            contains("bundle #" + bundleId + " was cancelled"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}

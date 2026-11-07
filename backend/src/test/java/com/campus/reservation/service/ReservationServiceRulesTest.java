package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.ReservationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The edge cases of {@link ReservationService} that the main happy-path tests don't reach:
 * malformed slots, bookings that vanish between two steps, and the "not found" answers.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceRulesTest {

    private static final Long USER_ID = 42L;
    private static final Long ASSET_ID = 10L;
    private static final Long OTHER_ASSET_ID = 20L;

    // Monday 2 Nov 2026, 10:00 in Bucharest.
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 2, 10, 0);

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

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                reservationRepository, catalogApi, currentUser, auditService, notificationService, clock);
    }

    private Reservation reservation(long id, LocalDateTime start, LocalDateTime end) {
        Reservation r = new Reservation(USER_ID, ASSET_ID, start, end, now.minusDays(1));
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private void assertSlotRejected(LocalDateTime start, LocalDateTime end, String code) {
        assertThatThrownBy(() -> reservationService.validateSlotRules(start, end, now))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", code);
    }

    @Test
    void anEmptyStatusListReturnsAllMyReservations() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByUserIdOrderByStartTimeAsc(eq(USER_ID), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        PageResponse<ReservationResponse> page = reservationService.getMyReservations(List.of(), 0, 10);

        assertThat(page.content()).isEmpty();
    }

    @Test
    void findBusyAssetIdsAsksForActiveReservationsOnly() {
        LocalDateTime start = now.plusHours(4);
        LocalDateTime end = now.plusHours(6);
        when(reservationRepository.findBusyAssetIds(start, end, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(ASSET_ID, OTHER_ASSET_ID));

        assertThat(reservationService.findBusyAssetIds(start, end)).containsExactly(ASSET_ID, OTHER_ASSET_ID);
    }

    @Test
    void unknownReservationIsNotFoundForGetByIdAndReturn() {
        when(reservationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.getById(999L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_FOUND");
        assertThatThrownBy(() -> reservationService.returnReservation(999L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_FOUND");
    }

    @Test
    void rejectsEveryKindOfMisalignedStartOrEnd() {
        LocalDateTime start = now.plusHours(4);
        LocalDateTime end = now.plusHours(6);

        assertSlotRejected(start.withNano(500), end, "SLOT_NOT_ALIGNED");
        assertSlotRejected(start.withMinute(15), end, "SLOT_NOT_ALIGNED");
        assertSlotRejected(start, end.withSecond(15), "SLOT_NOT_ALIGNED");
        assertSlotRejected(start, end.withNano(500), "SLOT_NOT_ALIGNED");
        assertSlotRejected(start, end.withMinute(15), "SLOT_NOT_ALIGNED");
    }

    @Test
    void rejectsBookingsLongerThanFourHours() {
        assertSlotRejected(now.plusHours(1), now.plusHours(6), "INVALID_DURATION");
    }

    @Test
    void rejectsSundayBookings() {
        assertSlotRejected(
                LocalDateTime.of(2026, 11, 8, 10, 0), LocalDateTime.of(2026, 11, 8, 12, 0), "OUTSIDE_OPENING_HOURS");
    }

    @Test
    void checkAvailabilityValidatesSlotRules() {
        LocalDateTime start = now.plusHours(4);
        LocalDateTime end = now.plusHours(2);

        assertThatThrownBy(() -> reservationService.checkAvailability(ASSET_ID, null, start, end))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TIME_RANGE");
    }

    @Test
    void movingAnUnknownOrNotConfirmedReservationDoesNothing() {
        when(reservationRepository.findById(777L)).thenReturn(Optional.empty());

        assertThat(reservationService.moveToAsset(777L, OTHER_ASSET_ID, "reason"))
                .isFalse();
        assertThat(reservationService.forceCancel(777L, "reason")).isFalse();
        assertThat(reservationService.markNoShow(777L)).isFalse();
        assertThat(reservationService.markOverdue(777L)).isFalse();
    }

    @Test
    void noShowAndOverdueSkipReservationsInTheWrongStatus() {
        Reservation confirmed = reservation(1L, now.plusHours(1), now.plusHours(2));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(confirmed));

        // A CONFIRMED booking was never checked out, so it can't become OVERDUE.
        assertThat(reservationService.markOverdue(1L)).isFalse();

        confirmed.markNoShow();
        assertThat(reservationService.markNoShow(1L)).isFalse();
        assertThat(reservationService.forceCancel(1L, "reason")).isFalse();
    }

    @Test
    void moveToAnAssetThatIsNotBookableAnymoreIsRejected() {
        Reservation confirmed = reservation(2L, now.plusHours(4), now.plusHours(6));
        when(reservationRepository.findById(2L)).thenReturn(Optional.of(confirmed));
        when(catalogApi.isBookable(OTHER_ASSET_ID)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.moveToAsset(2L, OTHER_ASSET_ID, "reason"))
                .isInstanceOf(SubstituteNoLongerFreeException.class);
        verify(catalogApi).lockForBooking(OTHER_ASSET_ID);
    }

    @Test
    void moveToAnAssetThatIsBookedMeanwhileIsRejected() {
        Reservation confirmed = reservation(3L, now.plusHours(4), now.plusHours(6));
        when(reservationRepository.findById(3L)).thenReturn(Optional.of(confirmed));
        when(catalogApi.isBookable(OTHER_ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(
                        OTHER_ASSET_ID,
                        confirmed.getStartTime(),
                        confirmed.getEndTime(),
                        ReservationStatus.ACTIVE_STATUSES,
                        3L))
                .thenReturn(true);

        assertThatThrownBy(() -> reservationService.moveToAsset(3L, OTHER_ASSET_ID, "reason"))
                .isInstanceOf(SubstituteNoLongerFreeException.class);
    }

    @Test
    void checkOutSucceedsWhenTheAssetHolderIsTheSameReservation() {
        Reservation confirmed = reservation(4L, now, now.plusHours(2));
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findById(4L)).thenReturn(Optional.of(confirmed));
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.findFirstByAssetIdAndStatusInOrderByStartTimeDesc(eq(ASSET_ID), any()))
                .thenReturn(Optional.of(confirmed));

        ReservationResponse response = reservationService.checkOutReservation(4L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_OUT);
    }
}

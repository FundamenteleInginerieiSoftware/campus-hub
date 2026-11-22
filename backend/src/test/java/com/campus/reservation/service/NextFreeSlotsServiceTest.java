package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.NextFreeSlotsResponse;
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

@ExtendWith(MockitoExtension.class)
class NextFreeSlotsServiceTest {

    private static final Long ASSET_ID = 1L;
    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 99L;

    // Friday 2026-11-06 18:10 Europe/Bucharest (crosses weekend into Monday 2026-11-09)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T16:10:00Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationHoldRepository reservationHoldRepository;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private CurrentUser currentUser;

    private NextFreeSlotsService service;

    @BeforeEach
    void setUp() {
        service = new NextFreeSlotsService(
                reservationRepository, reservationHoldRepository, catalogApi, currentUser, clock);
    }

    @Test
    @DisplayName("getNextFreeSlots skips booked/foreign-held slots and weekends, ignores own hold")
    void getNextFreeSlots_skipsBusyAndWeekends() {
        LocalDateTime now = LocalDateTime.now(clock);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(currentUser.id()).thenReturn(USER_ID);

        // Friday 18:30-19:30 is booked by someone else; 19:00-20:00 is held by caller (ignored).
        Reservation fridayBooking = new Reservation(
                OTHER_USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 6, 18, 30),
                LocalDateTime.of(2026, 11, 6, 19, 30),
                now.minusDays(1));
        ReservationHold ownHold = new ReservationHold(
                ASSET_ID,
                USER_ID,
                LocalDateTime.of(2026, 11, 9, 8, 0),
                LocalDateTime.of(2026, 11, 9, 9, 0),
                now.plusMinutes(1),
                now);
        when(reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                        ASSET_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(fridayBooking));
        when(reservationHoldRepository.findByAssetIdAndStatusAndExpiresAtAfter(
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of(ownHold));

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, null, 60, 2);

        assertThat(response.bookable()).isTrue();
        assertThat(response.slots())
                .as(
                        "must skip Friday 18:30 conflict and weekend, returning Monday 08:00 and 08:30; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(2);
        assertThat(response.slots().get(0).startTime()).isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 0));
        assertThat(response.slots().get(1).startTime()).isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 30));
    }

    @Test
    @DisplayName("getNextFreeSlots treats OVERDUE booking as open-ended blocker and returns empty")
    void getNextFreeSlots_overdueBlocksOpenEnded() {
        LocalDateTime now = LocalDateTime.now(clock);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(currentUser.id()).thenReturn(USER_ID);

        Reservation overdue = new Reservation(
                OTHER_USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 6, 15, 0),
                LocalDateTime.of(2026, 11, 6, 16, 0),
                now.minusDays(1));
        overdue.checkOut(LocalDateTime.of(2026, 11, 6, 15, 0));
        overdue.markOverdue();

        when(reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                        ASSET_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(overdue));
        when(reservationHoldRepository.findByAssetIdAndStatusAndExpiresAtAfter(
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), any()))
                .thenReturn(List.of());

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, null, 60, 5);

        assertThat(response.slots())
                .as(
                        "OVERDUE asset blocks all subsequent slots until returned; see NextFreeSlotsService.overlapsActiveBookings")
                .isEmpty();
    }

    @Test
    @DisplayName("getNextFreeSlots returns bookable=false when asset is under maintenance")
    void getNextFreeSlots_nonBookableAssetReturnsEmpty() {
        AssetSummary maintenance =
                new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(maintenance);

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, null, 60, 5);

        assertThat(response.bookable()).isFalse();
        assertThat(response.slots()).isEmpty();
    }

    @Test
    @DisplayName("getNextFreeSlots rejects unaligned durations with INVALID_DURATION")
    void getNextFreeSlots_rejectsInvalidDuration() {
        assertThatThrownBy(() -> service.getNextFreeSlots(ASSET_ID, null, 45, 5))
                .as("non-30m aligned duration must throw INVALID_DURATION; see NextFreeSlotsService.getNextFreeSlots")
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("INVALID_DURATION");
    }
}

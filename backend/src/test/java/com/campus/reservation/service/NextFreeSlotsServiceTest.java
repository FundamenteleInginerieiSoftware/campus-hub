package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.NotFoundException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    private void stubAvailableAssetWithNoBusySlots() {
        stubAvailableAssetWithNoBusySlots(ReservationServiceSupport.truncatedNow(clock));
    }

    private void stubAvailableAssetWithNoBusySlots(LocalDateTime expectedNow) {
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                        ASSET_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of());
        when(reservationHoldRepository.findByAssetIdAndStatusAndExpiresAtAfter(
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), eq(expectedNow)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("getNextFreeSlots skips active reservations and weekend days")
    void getNextFreeSlots_skipsBusyAndWeekends() {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(currentUser.id()).thenReturn(USER_ID);

        // Friday 18:30-19:30 is booked (so Friday 18:30-19:30 and 19:00-20:00 both conflict for 60m).
        Reservation fridayBooking = new Reservation(
                OTHER_USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 6, 18, 30),
                LocalDateTime.of(2026, 11, 6, 19, 30),
                now.minusDays(1));
        when(reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                        ASSET_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(fridayBooking));
        when(reservationHoldRepository.findByAssetIdAndStatusAndExpiresAtAfter(
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), eq(now)))
                .thenReturn(List.of());

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, null, 60, 2);

        assertThat(response.bookable())
                .as("available asset must report bookable=true; see NextFreeSlotsService.getNextFreeSlots")
                .isTrue();
        assertThat(response.slots())
                .as(
                        "must skip Friday 18:30 conflict and weekend, returning Monday 08:00 and 08:30; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(2);
        assertThat(response.slots().get(0).startTime())
                .as("first free slot after weekend must start Monday 08:00; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 0));
        assertThat(response.slots().get(1).startTime())
                .as("second free slot after weekend must start Monday 08:30; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 30));
    }

    @Test
    @DisplayName("getNextFreeSlots offers a free slot immediately before a busy booking and skips overlapping starts")
    void getNextFreeSlots_offersSlotBeforeBusyBlock() {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(currentUser.id()).thenReturn(USER_ID);

        // Monday 09:00-10:00 is booked: 08:00-09:00 fits right before it, 08:30/09:00/09:30 overlap, 10:00-11:00 fits
        // after.
        Reservation mondayBooking = new Reservation(
                OTHER_USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 9, 9, 0),
                LocalDateTime.of(2026, 11, 9, 10, 0),
                now.minusDays(1));
        when(reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                        ASSET_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of(mondayBooking));
        when(reservationHoldRepository.findByAssetIdAndStatusAndExpiresAtAfter(
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), eq(now)))
                .thenReturn(List.of());

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, LocalDateTime.of(2026, 11, 9, 8, 0), 60, 2);

        assertThat(response.slots())
                .as(
                        "adjacent slot 08:00-09:00 before busy 09:00-10:00 and 10:00-11:00 after it must both be returned; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(2);
        assertThat(response.slots().get(0).startTime())
                .as("slot ending at busy start 09:00 must not be blocked; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 0));
        assertThat(response.slots().get(1).startTime())
                .as("slot starting at busy end 10:00 must be offered next; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
    }

    @Test
    @DisplayName("getNextFreeSlots clamps 'from' in the past up to ceilToHalfHour(now)")
    void getNextFreeSlots_clampsPastFromToNow() {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        stubAvailableAssetWithNoBusySlots(now);

        // clock is Friday 2026-11-06 18:10 -> ceilToHalfHour(now) is 18:30; past 'from' must not return earlier slots.
        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, now.minusDays(1), 60, 1);

        assertThat(response.slots())
                .as("past 'from' must still yield the first upcoming slot; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(1);
        assertThat(response.slots().get(0).startTime())
                .as(
                        "past 'from' must be clamped to ceilToHalfHour(now) = 18:30; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 18, 30));
    }

    @Test
    @DisplayName("getNextFreeSlots blocks slots overlapping another student's active hold")
    void getNextFreeSlots_skipsForeignHold() {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(currentUser.id()).thenReturn(USER_ID);

        ReservationHold foreignHold = new ReservationHold(
                ASSET_ID,
                OTHER_USER_ID,
                LocalDateTime.of(2026, 11, 9, 9, 0),
                LocalDateTime.of(2026, 11, 9, 10, 0),
                now.plusMinutes(1),
                now);
        when(reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                        ASSET_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of());
        when(reservationHoldRepository.findByAssetIdAndStatusAndExpiresAtAfter(
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), eq(now)))
                .thenReturn(List.of(foreignHold));

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, LocalDateTime.of(2026, 11, 9, 8, 0), 60, 2);

        assertThat(response.slots())
                .as(
                        "foreign hold 09:00-10:00 must allow 08:00-09:00 before it, skip 08:30/09:00/09:30, and offer 10:00-11:00; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(2);
        assertThat(response.slots().get(0).startTime())
                .as(
                        "slot 08:00-09:00 ending at foreign hold start must be offered; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 0));
        assertThat(response.slots().get(1).startTime())
                .as(
                        "adjacent slot starting at foreign hold end 10:00 must be offered; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
    }

    @Test
    @DisplayName("getNextFreeSlots does not block slots held by the caller themselves")
    void getNextFreeSlots_ignoresOwnHold() {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(currentUser.id()).thenReturn(USER_ID);

        ReservationHold ownHold = new ReservationHold(
                ASSET_ID,
                USER_ID,
                LocalDateTime.of(2026, 11, 9, 8, 0),
                LocalDateTime.of(2026, 11, 9, 9, 0),
                now.plusMinutes(1),
                now);
        when(reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                        ASSET_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(List.of());
        when(reservationHoldRepository.findByAssetIdAndStatusAndExpiresAtAfter(
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), eq(now)))
                .thenReturn(List.of(ownHold));

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, LocalDateTime.of(2026, 11, 9, 8, 0), 60, 1);

        assertThat(response.slots())
                .as("caller's own hold must not block 08:00-09:00; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(1);
        assertThat(response.slots().get(0).startTime())
                .as("caller's own held slot 08:00 must be returned; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 0));
    }

    @Test
    @DisplayName("getNextFreeSlots treats OVERDUE booking as open-ended blocker and returns empty")
    void getNextFreeSlots_overdueBlocksOpenEnded() {
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
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
                        eq(ASSET_ID), eq(HoldStatus.ACTIVE), eq(now)))
                .thenReturn(List.of());

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, null, 60, 5);

        assertThat(response.bookable())
                .as(
                        "available asset with overdue booking still reports bookable=true; see NextFreeSlotsService.getNextFreeSlots")
                .isTrue();
        assertThat(response.slots())
                .as(
                        "OVERDUE asset blocks all subsequent slots until returned; see NextFreeSlotsService.getNextFreeSlots")
                .isEmpty();
    }

    @Test
    @DisplayName("getNextFreeSlots stops at exact 14-day horizon on slot startTime")
    void getNextFreeSlots_respectsExact14DayHorizon() {
        // Friday 2026-11-06 14:00:00 Europe/Bucharest -> horizonEnd = Friday 2026-11-20 14:00:00
        Clock alignedClock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneId.of("Europe/Bucharest"));
        NextFreeSlotsService alignedService = new NextFreeSlotsService(
                reservationRepository, reservationHoldRepository, catalogApi, currentUser, alignedClock);
        stubAvailableAssetWithNoBusySlots(ReservationServiceSupport.truncatedNow(alignedClock));

        NextFreeSlotsResponse atEdge =
                alignedService.getNextFreeSlots(ASSET_ID, LocalDateTime.of(2026, 11, 20, 14, 0), 60, 5);

        assertThat(atEdge.slots())
                .as(
                        "slot starting at exact now+14d (14:00) must be offered while 14:30 must be excluded; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(1);
        assertThat(atEdge.slots().get(0).startTime())
                .as("offered slot at 14-day horizon must start at 14:00; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 20, 14, 0));

        NextFreeSlotsResponse pastEdge =
                alignedService.getNextFreeSlots(ASSET_ID, LocalDateTime.of(2026, 11, 20, 14, 30), 60, 5);
        assertThat(pastEdge.slots())
                .as("from past now+14d (14:30) must return empty slots; see NextFreeSlotsService.getNextFreeSlots")
                .isEmpty();
    }

    @Test
    @DisplayName("getNextFreeSlots returns bookable=false when asset is UNDER_MAINTENANCE")
    void getNextFreeSlots_underMaintenanceReturnsNotBookable() {
        AssetSummary maintenance =
                new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(maintenance);

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, null, 60, 5);

        assertThat(response.bookable())
                .as("UNDER_MAINTENANCE asset must report bookable=false; see NextFreeSlotsService.getNextFreeSlots")
                .isFalse();
        assertThat(response.slots())
                .as("UNDER_MAINTENANCE asset must return empty slots; see NextFreeSlotsService.getNextFreeSlots")
                .isEmpty();
    }

    @Test
    @DisplayName("getNextFreeSlots throws 404 ASSET_NOT_FOUND for students querying a RETIRED asset (#278, #362)")
    void getNextFreeSlots_retiredAssetThrowsNotFoundForStudent() {
        AssetSummary retired = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.RETIRED);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(retired);
        when(currentUser.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> service.getNextFreeSlots(ASSET_ID, null, 60, 5))
                .as(
                        "RETIRED asset must be hidden from students with 404 ASSET_NOT_FOUND; see NextFreeSlotsService.getNextFreeSlots")
                .isInstanceOf(NotFoundException.class)
                .extracting(ex -> ((NotFoundException) ex).getCode())
                .isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    @DisplayName("getNextFreeSlots returns bookable=false for admins querying a RETIRED asset")
    void getNextFreeSlots_retiredAssetReturnsNotBookableForAdmin() {
        AssetSummary retired = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.RETIRED);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(retired);
        when(currentUser.isAdmin()).thenReturn(true);

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, null, 60, 5);

        assertThat(response.bookable())
                .as(
                        "RETIRED asset queried by admin must report bookable=false; see NextFreeSlotsService.getNextFreeSlots")
                .isFalse();
        assertThat(response.slots())
                .as("RETIRED asset queried by admin must return empty slots; see NextFreeSlotsService.getNextFreeSlots")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 45, 270})
    @DisplayName("getNextFreeSlots rejects invalid durations (below 30, unaligned, above 240) with INVALID_DURATION")
    void getNextFreeSlots_rejectsInvalidDuration(int durationMinutes) {
        assertThatThrownBy(() -> service.getNextFreeSlots(ASSET_ID, null, durationMinutes, 5))
                .as("duration " + durationMinutes
                        + " must throw INVALID_DURATION; see NextFreeSlotsService.getNextFreeSlots")
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("INVALID_DURATION");
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 240})
    @DisplayName("getNextFreeSlots accepts duration bounds 30 and 240 minutes")
    void getNextFreeSlots_acceptsBoundaryDurations(int durationMinutes) {
        stubAvailableAssetWithNoBusySlots();

        NextFreeSlotsResponse response =
                service.getNextFreeSlots(ASSET_ID, LocalDateTime.of(2026, 11, 9, 8, 0), durationMinutes, 1);

        assertThat(response.durationMinutes())
                .as("response must echo boundary duration " + durationMinutes
                        + "; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(durationMinutes);
        assertThat(response.slots())
                .as("boundary duration " + durationMinutes
                        + " must yield a slot; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(1);
        assertThat(response.slots().get(0).endTime())
                .as("slot end must equal start + " + durationMinutes + "m; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 0).plusMinutes(durationMinutes));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    @DisplayName("getNextFreeSlots rejects out-of-range limit values (0 and 11) with INVALID_PARAMETER")
    void getNextFreeSlots_rejectsInvalidLimit(int limit) {
        assertThatThrownBy(() -> service.getNextFreeSlots(ASSET_ID, null, 60, limit))
                .as("limit " + limit + " must throw INVALID_PARAMETER; see NextFreeSlotsService.getNextFreeSlots")
                .isInstanceOf(InvalidParameterException.class)
                .extracting(ex -> ((InvalidParameterException) ex).getCode())
                .isEqualTo("INVALID_PARAMETER");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    @DisplayName("getNextFreeSlots honors limit bounds 1 and 10")
    void getNextFreeSlots_honorsLimitBounds(int limit) {
        stubAvailableAssetWithNoBusySlots();

        NextFreeSlotsResponse response =
                service.getNextFreeSlots(ASSET_ID, LocalDateTime.of(2026, 11, 9, 8, 0), 30, limit);

        assertThat(response.slots())
                .as("limit " + limit + " must return exact count of slots; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(limit);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-09T10:00:00,       2026-11-09T10:00:00",
        "2026-11-09T10:00:01,       2026-11-09T10:30:00",
        "2026-11-09T10:15:00,       2026-11-09T10:30:00",
        "2026-11-09T10:30:00,       2026-11-09T10:30:00",
        "2026-11-09T10:30:01,       2026-11-09T11:00:00",
        "2026-11-09T10:30:00.500,   2026-11-09T11:00:00",
        "2026-11-09T10:45:00,       2026-11-09T11:00:00"
    })
    @DisplayName("getNextFreeSlots rounds unaligned 'from' timestamps up to the next :00 or :30 mark")
    void getNextFreeSlots_ceilsFromToHalfHour(LocalDateTime from, LocalDateTime expectedStart) {
        stubAvailableAssetWithNoBusySlots();

        NextFreeSlotsResponse response = service.getNextFreeSlots(ASSET_ID, from, 30, 1);

        assertThat(response.slots())
                .as("ceilToHalfHour for " + from + " must yield a slot; see NextFreeSlotsService.getNextFreeSlots")
                .hasSize(1);
        assertThat(response.slots().get(0).startTime())
                .as("from=" + from + " must round up to " + expectedStart
                        + "; see NextFreeSlotsService.getNextFreeSlots")
                .isEqualTo(expectedStart);
    }
}

package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.NotFoundException;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CalendarFeedServiceTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-10T09:30:00Z");
    private static final String VALID_TOKEN = "a".repeat(43);
    private static final Long USER_ID = 42L;

    @Mock
    private CalendarTokenService calendarTokenService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CatalogApi catalogApi;

    private CalendarFeedService calendarFeedService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, CAMPUS_ZONE);
        calendarFeedService = new CalendarFeedService(calendarTokenService, reservationRepository, catalogApi, clock);
    }

    @Test
    void unknownTokenIs404() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendarFeedService.feedFor(VALID_TOKEN))
                .as("Unknown token must throw NotFoundException; see CalendarFeedService.feedFor")
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> assertThat(((NotFoundException) ex).getCode())
                        .as("Error code must be CALENDAR_FEED_NOT_FOUND; see CalendarFeedService.feedFor")
                        .isEqualTo("CALENDAR_FEED_NOT_FOUND"));

        verifyNoInteractions(reservationRepository, catalogApi);
    }

    @Test
    void asksThirtyDaysBackAndAhead() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));
        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of());

        calendarFeedService.feedFor(VALID_TOKEN);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reservationRepository)
                .findByUserIdOverlappingRange(eq(USER_ID), fromCaptor.capture(), toCaptor.capture());

        // 2026-11-10T09:30:00Z in Europe/Bucharest (UTC+2) = 2026-11-10T11:30:00
        LocalDateTime expectedNow = LocalDateTime.of(2026, 11, 10, 11, 30, 0);
        assertThat(fromCaptor.getValue())
                .as("Feed range must start 30 days before campus-now; see CalendarFeedService.feedFor")
                .isEqualTo(expectedNow.minusDays(30));
        assertThat(toCaptor.getValue())
                .as("Feed range must end 30 days after campus-now; see CalendarFeedService.feedFor")
                .isEqualTo(expectedNow.plusDays(30));
    }

    @Test
    void hidesCancelledForceCancelledAndNoShow() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));

        Reservation cancelled = reservation(1L, 7L, LocalDateTime.of(2026, 11, 12, 10, 0));
        cancelled.cancel(LocalDateTime.of(2026, 11, 11, 10, 0), "Changed mind");

        Reservation forceCancelled = reservation(2L, 7L, LocalDateTime.of(2026, 11, 13, 10, 0));
        forceCancelled.forceCancel(LocalDateTime.of(2026, 11, 11, 11, 0), "Asset damaged");

        Reservation noShow = reservation(3L, 7L, LocalDateTime.of(2026, 11, 14, 10, 0));
        noShow.markNoShow();

        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(cancelled, forceCancelled, noShow));

        String ics = calendarFeedService.feedFor(VALID_TOKEN);

        assertThat(ics)
                .as(
                        "CANCELLED, FORCE_CANCELLED, and NO_SHOW bookings must be excluded from the feed; see CalendarFeedService.feedFor")
                .doesNotContain("BEGIN:VEVENT");
        verifyNoInteractions(catalogApi);
    }

    @Test
    void keepsReturned() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));

        Reservation returned = reservation(15L, 7L, LocalDateTime.of(2026, 11, 9, 10, 0));
        returned.checkOut(LocalDateTime.of(2026, 11, 9, 10, 0));
        returned.returnItem(LocalDateTime.of(2026, 11, 9, 11, 30));

        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(returned));
        when(catalogApi.getAsset(7L))
                .thenReturn(new AssetSummary(7L, "Oscilloscope DS1054Z #2", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        String ics = calendarFeedService.feedFor(VALID_TOKEN);

        assertThat(ics)
                .as("RETURNED bookings must remain visible in the feed; see CalendarFeedService.feedFor")
                .contains("UID:reservation-15@campus-hub\r\n")
                .contains("Status: RETURNED");
    }

    @Test
    void usesTheCurrentAssetName() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));

        Reservation confirmed = reservation(123L, 7L, LocalDateTime.of(2026, 11, 16, 10, 0));
        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(confirmed));
        when(catalogApi.getAsset(7L))
                .thenReturn(new AssetSummary(7L, "Oscilloscope DS1054Z #2", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        String ics = calendarFeedService.feedFor(VALID_TOKEN);

        assertThat(ics)
                .as("SUMMARY must use the current asset name from CatalogApi; see CalendarFeedService.toCalendarEvent")
                .contains("SUMMARY:Oscilloscope DS1054Z #2\r\n")
                .contains("UID:reservation-123@campus-hub\r\n")
                .contains("DTSTART:20261116T080000Z\r\n")
                .contains("DTEND:20261116T100000Z\r\n");
    }

    @Test
    void overdueGetsAPrefix() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));

        Reservation overdue = reservation(44L, 7L, LocalDateTime.of(2026, 11, 9, 10, 0));
        overdue.checkOut(LocalDateTime.of(2026, 11, 9, 10, 0));
        overdue.markOverdue();

        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(overdue));
        when(catalogApi.getAsset(7L))
                .thenReturn(new AssetSummary(7L, "Oscilloscope DS1054Z #2", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        String ics = calendarFeedService.feedFor(VALID_TOKEN);

        assertThat(ics)
                .as("OVERDUE bookings must prefix SUMMARY with 'OVERDUE: '; see CalendarFeedService.toCalendarEvent")
                .contains("SUMMARY:OVERDUE: Oscilloscope DS1054Z #2\r\n");
    }

    @Test
    void asksTheCatalogOncePerAsset() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));

        Reservation r1 = reservation(1L, 7L, LocalDateTime.of(2026, 11, 11, 10, 0));
        Reservation r2 = reservation(2L, 7L, LocalDateTime.of(2026, 11, 12, 10, 0));
        Reservation r3 = reservation(3L, 7L, LocalDateTime.of(2026, 11, 13, 10, 0));

        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(r1, r2, r3));
        when(catalogApi.getAsset(7L))
                .thenReturn(new AssetSummary(7L, "Oscilloscope DS1054Z #2", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));

        calendarFeedService.feedFor(VALID_TOKEN);

        verify(catalogApi, times(1)).getAsset(7L);
    }

    @Test
    void missingAssetStillListsTheBooking() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));

        Reservation confirmed = reservation(55L, 7L, LocalDateTime.of(2026, 11, 16, 10, 0));
        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(confirmed));
        when(catalogApi.getAsset(7L)).thenThrow(new NotFoundException("ASSET_NOT_FOUND", "Asset 7 not found"));

        String ics = calendarFeedService.feedFor(VALID_TOKEN);

        assertThat(ics)
                .as("Missing asset must fall back to Equipment #<id>; see CalendarFeedService.toCalendarEvent")
                .contains("SUMMARY:Equipment #7\r\n")
                .contains("Model: Unknown");
    }

    @Test
    void noBookingsGivesAnEmptyCalendar() {
        when(calendarTokenService.findActiveUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));
        when(reservationRepository.findByUserIdOverlappingRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of());

        String ics = calendarFeedService.feedFor(VALID_TOKEN);

        assertThat(ics)
                .as(
                        "Empty bookings list must still produce a valid VCALENDAR without VEVENTs; see CalendarFeedService.feedFor")
                .startsWith("BEGIN:VCALENDAR\r\n")
                .endsWith("END:VCALENDAR\r\n")
                .doesNotContain("BEGIN:VEVENT");
    }

    private static Reservation reservation(Long id, Long assetId, LocalDateTime start) {
        Reservation reservation = new Reservation(USER_ID, assetId, start, start.plusHours(2), start.minusDays(1));
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }
}

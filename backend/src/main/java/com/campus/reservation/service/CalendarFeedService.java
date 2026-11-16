package com.campus.reservation.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.NotFoundException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the personalized RFC 5545 iCalendar ({@code .ics}) feed for a private subscription token.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CalendarFeedService {

    static final int PAST_DAYS = 30;
    static final int FUTURE_DAYS = 30;

    private static final String CALENDAR_NAME = "Campus Hub";
    private static final Set<ReservationStatus> SHOWN_STATUSES = EnumSet.of(
            ReservationStatus.CONFIRMED,
            ReservationStatus.CHECKED_OUT,
            ReservationStatus.OVERDUE,
            ReservationStatus.RETURNED);

    private final CalendarTokenService calendarTokenService;
    private final ReservationRepository reservationRepository;
    private final CatalogApi catalogApi;
    private final Clock clock;

    /**
     * Resolves {@code token} to an active user and renders their reservations within the
     * {@code [-30d, +30d]} campus window as an iCalendar string.
     */
    public String feedFor(String token) {
        Long userId = calendarTokenService
                .findActiveUserId(token)
                .orElseThrow(() -> new NotFoundException("CALENDAR_FEED_NOT_FOUND", "Calendar link not found"));

        LocalDateTime campusNow = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime from = campusNow.minusDays(PAST_DAYS);
        LocalDateTime to = campusNow.plusDays(FUTURE_DAYS);

        List<Reservation> reservations = reservationRepository.findByUserIdOverlappingRange(userId, from, to);

        Map<Long, Optional<AssetSummary>> assetCache = new HashMap<>();
        ZoneId campusZone = clock.getZone();

        List<CalendarEvent> events = reservations.stream()
                // An event that leaves the feed is removed by the calendar app, which is what a
                // cancellation or no-show should look like.
                .filter(r -> SHOWN_STATUSES.contains(r.getStatus()))
                .map(r -> toCalendarEvent(r, campusZone, assetCache))
                .toList();

        return IcsWriter.write(CALENDAR_NAME, events, clock.instant());
    }

    private CalendarEvent toCalendarEvent(
            Reservation reservation, ZoneId campusZone, Map<Long, Optional<AssetSummary>> assetCache) {
        Long assetId = reservation.getAssetId();
        Optional<AssetSummary> asset = assetCache.computeIfAbsent(assetId, this::loadAssetSafely);

        String assetName = asset.map(AssetSummary::name).orElse("Equipment #" + assetId);
        String assetModel = asset.map(AssetSummary::model).orElse("Unknown");

        // Bookings are stored as campus local time (Europe/Bucharest); convert via the shared
        // Clock's zone so IcsWriter emits accurate UTC timestamps across winter and summer time.
        Instant start = reservation.getStartTime().atZone(campusZone).toInstant();
        Instant end = reservation.getEndTime().atZone(campusZone).toInstant();

        String summary = (reservation.getStatus() == ReservationStatus.OVERDUE ? "OVERDUE: " : "") + assetName;
        String description = "Campus Hub reservation #"
                + reservation.getId()
                + "\nModel: "
                + assetModel
                + "\nStatus: "
                + reservation.getStatus().name();

        return new CalendarEvent(
                "reservation-" + reservation.getId() + "@campus-hub", start, end, summary, description);
    }

    private Optional<AssetSummary> loadAssetSafely(Long assetId) {
        try {
            return Optional.of(catalogApi.getAsset(assetId));
        } catch (NotFoundException ex) {
            return Optional.empty();
        }
    }
}

package com.campus.reservation.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.SlotRecommendationResponse;
import com.campus.reservation.web.SlotRecommendationResponse.SubstituteAssetSuggestion;
import com.campus.reservation.web.SlotRecommendationResponse.TimeWindowSuggestion;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-CRUD recommendation engine that uses:
 * <ol>
 *   <li><b>Sweep-line interval subtraction</b> over lab opening hours (08:00-20:00) to find the
 *       closest conflict-free time windows on the requested asset;</li>
 *   <li><b>Multi-criteria heuristic scoring</b> to rank free substitute assets (same model, same
 *       category, same lab room, and daily load balancing) for the student's exact desired slot.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SlotRecommendationService {

    private static final LocalTime LAB_OPEN = LocalTime.of(8, 0);
    private static final LocalTime LAB_CLOSE = LocalTime.of(20, 0);
    private static final int MAX_SLOT_SUGGESTIONS = 5;

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final CatalogApi catalogApi;
    private final Clock clock;

    /**
     * Computes alternative time slots on {@code assetId} and ranked substitute assets for
     * {@code [desiredStart, desiredEnd]}.
     */
    public SlotRecommendationResponse recommend(Long assetId, LocalDateTime desiredStart, LocalDateTime desiredEnd) {
        LocalDateTime now = LocalDateTime.now(clock);
        // Reuse shared slot validation so weekend slots, >14-day horizon, and unaligned boundaries
        // are rejected consistently with ReservationService.book().
        reservationService.validateSlotRules(desiredStart, desiredEnd, now);
        long durationMinutes = Duration.between(desiredStart, desiredEnd).toMinutes();

        AssetSummary targetAsset = catalogApi.getAsset(assetId);

        boolean exactFree = catalogApi.isBookable(assetId)
                && !desiredStart.isBefore(now)
                && !reservationRepository.existsOverlap(
                        assetId, desiredStart, desiredEnd, ReservationStatus.ACTIVE_STATUSES, -1L);

        List<TimeWindowSuggestion> alternativeSlots =
                computeSweepLineAlternativeSlots(assetId, desiredStart, durationMinutes, now);

        List<SubstituteAssetSuggestion> substitutes = rankAvailableSubstitutes(targetAsset, desiredStart, desiredEnd);

        return new SlotRecommendationResponse(
                targetAsset.id(),
                targetAsset.name(),
                desiredStart,
                desiredEnd,
                exactFree,
                alternativeSlots,
                substitutes);
    }

    /**
     * Sweep-line algorithm that subtracts existing active bookings from the day's [08:00, 20:00]
     * operating window and extracts candidate slots of {@code durationMinutes} sorted by proximity
     * to {@code desiredStart}.
     */
    private List<TimeWindowSuggestion> computeSweepLineAlternativeSlots(
            Long assetId, LocalDateTime desiredStart, long durationMinutes, LocalDateTime now) {
        if (!catalogApi.isBookable(assetId)) {
            return List.of();
        }

        LocalDateTime dayStart = LocalDateTime.of(desiredStart.toLocalDate(), LAB_OPEN);
        LocalDateTime dayEnd = LocalDateTime.of(desiredStart.toLocalDate(), LAB_CLOSE);
        if (dayStart.isBefore(now)) {
            dayStart = roundUpToNextHalfHour(now);
        }
        if (!dayStart.plusMinutes(durationMinutes).isBefore(dayEnd)
                && !dayStart.plusMinutes(durationMinutes).isEqual(dayEnd)) {
            return List.of();
        }

        List<Reservation> occupied = reservationRepository.findActiveByAssetInWindow(
                assetId, dayStart, dayEnd, ReservationStatus.ACTIVE_STATUSES);

        // Sweep cursor from dayStart to dayEnd to collect contiguous free intervals
        List<FreeGap> freeGaps = new ArrayList<>();
        LocalDateTime cursor = dayStart;

        for (Reservation r : occupied) {
            LocalDateTime busyStart = r.getStartTime().isBefore(dayStart) ? dayStart : r.getStartTime();
            LocalDateTime busyEnd = r.getEndTime().isAfter(dayEnd) ? dayEnd : r.getEndTime();

            if (busyStart.isAfter(cursor)) {
                freeGaps.add(new FreeGap(cursor, busyStart));
            }
            if (busyEnd.isAfter(cursor)) {
                cursor = busyEnd;
            }
        }
        if (cursor.isBefore(dayEnd)) {
            freeGaps.add(new FreeGap(cursor, dayEnd));
        }

        // Slice free gaps into 30-minute aligned candidate windows of the requested duration
        List<TimeWindowSuggestion> candidates = new ArrayList<>();
        for (FreeGap gap : freeGaps) {
            LocalDateTime slotStart = gap.start();
            while (!slotStart.plusMinutes(durationMinutes).isAfter(gap.end())) {
                LocalDateTime slotEnd = slotStart.plusMinutes(durationMinutes);
                long shiftMinutes =
                        Math.abs(Duration.between(desiredStart, slotStart).toMinutes());
                if (shiftMinutes > 0) {
                    candidates.add(new TimeWindowSuggestion(slotStart, slotEnd, shiftMinutes));
                }
                slotStart = slotStart.plusMinutes(30);
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparingLong(TimeWindowSuggestion::shiftMinutesFromDesired)
                        .thenComparing(TimeWindowSuggestion::startTime))
                .limit(MAX_SLOT_SUGGESTIONS)
                .toList();
    }

    /**
     * Ranks available substitute assets that are conflict-free during {@code [desiredStart, desiredEnd]}
     * using a weighted compatibility score (same model: +60, same category: +30, same lab room: +30,
     * zero bookings on that day: +10).
     */
    private List<SubstituteAssetSuggestion> rankAvailableSubstitutes(
            AssetSummary targetAsset, LocalDateTime desiredStart, LocalDateTime desiredEnd) {
        Map<Long, AssetSummary> candidateMap = new LinkedHashMap<>();
        for (AssetSummary a : catalogApi.findAvailableByModel(targetAsset.model(), targetAsset.id())) {
            candidateMap.put(a.id(), a);
        }
        for (AssetSummary a : catalogApi.findAvailableByCategory(targetAsset.categoryId(), targetAsset.id())) {
            candidateMap.putIfAbsent(a.id(), a);
        }

        LocalDateTime dayStart = LocalDateTime.of(desiredStart.toLocalDate(), LAB_OPEN);
        LocalDateTime dayEnd = LocalDateTime.of(desiredStart.toLocalDate(), LAB_CLOSE);

        List<SubstituteAssetSuggestion> scored = new ArrayList<>();
        for (AssetSummary candidate : candidateMap.values()) {
            boolean busy = reservationRepository.existsOverlap(
                    candidate.id(), desiredStart, desiredEnd, ReservationStatus.ACTIVE_STATUSES, -1L);
            if (busy) {
                continue;
            }

            boolean sameModel = Objects.equals(candidate.model(), targetAsset.model());
            boolean sameRoom = Objects.equals(candidate.labRoomId(), targetAsset.labRoomId());
            boolean idleAllDay = reservationRepository
                    .findActiveByAssetInWindow(candidate.id(), dayStart, dayEnd, ReservationStatus.ACTIVE_STATUSES)
                    .isEmpty();

            int score = (sameModel ? 60 : 30) + (sameRoom ? 30 : 0) + (idleAllDay ? 10 : 0);

            scored.add(new SubstituteAssetSuggestion(
                    candidate.id(),
                    candidate.name(),
                    candidate.model(),
                    candidate.labRoomId(),
                    sameModel,
                    sameRoom,
                    score));
        }

        return scored.stream()
                .sorted(Comparator.comparingInt(SubstituteAssetSuggestion::compatibilityScore)
                        .reversed()
                        .thenComparing(SubstituteAssetSuggestion::assetId))
                .toList();
    }

    private LocalDateTime roundUpToNextHalfHour(LocalDateTime dt) {
        LocalDateTime truncated = dt.withSecond(0).withNano(0);
        int minute = dt.getMinute();
        if (minute == 0 && dt.getSecond() == 0 && dt.getNano() == 0) {
            return truncated;
        }
        if (minute < 30 || (minute == 30 && dt.getSecond() == 0 && dt.getNano() == 0)) {
            return (minute == 30) ? truncated : truncated.withMinute(30);
        }
        return truncated.plusHours(1).withMinute(0);
    }

    private record FreeGap(LocalDateTime start, LocalDateTime end) {}
}

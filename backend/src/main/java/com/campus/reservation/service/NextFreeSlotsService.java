package com.campus.reservation.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.FreeSlotOption;
import com.campus.reservation.web.NextFreeSlotsResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recommends the next chronologically free, rule-aligned time slots for a specific asset (#61).
 * Leaves substitute-unit ranking to {@code ReservationService.checkAvailability} (#286).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NextFreeSlotsService {

    private static final LocalTime OPEN_TIME = LocalTime.of(8, 0);
    private static final LocalTime CLOSE_TIME = LocalTime.of(20, 0);
    private static final int STEP_MINUTES = 30;
    private static final int MAX_DURATION_MINUTES = 240;
    private static final int MAX_ADVANCE_DAYS = 14;

    private final ReservationRepository reservationRepository;
    private final ReservationHoldRepository reservationHoldRepository;
    private final CatalogApi catalogApi;
    private final CurrentUser currentUser;
    private final Clock clock;

    /**
     * Finds up to {@code limit} upcoming free slots of {@code durationMinutes} on {@code assetId},
     * starting at or after {@code from} within the 14-day weekday 08:00–20:00 booking window.
     */
    public NextFreeSlotsResponse getNextFreeSlots(Long assetId, LocalDateTime from, int durationMinutes, int limit) {
        if (durationMinutes < STEP_MINUTES
                || durationMinutes > MAX_DURATION_MINUTES
                || durationMinutes % STEP_MINUTES != 0) {
            throw new BusinessRuleException(
                    "INVALID_DURATION", "Duration must be a multiple of 30 minutes between 30 and 240");
        }
        if (limit < 1 || limit > 10) {
            throw new InvalidParameterException("limit must be between 1 and 10");
        }

        AssetSummary asset = catalogApi.getAsset(assetId);
        if (asset.status() != AssetStatus.AVAILABLE) {
            return new NextFreeSlotsResponse(assetId, durationMinutes, false, List.of());
        }

        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);
        LocalDateTime horizonEnd = now.plusDays(MAX_ADVANCE_DAYS);
        LocalDateTime searchStart = ceilToHalfHour((from != null && from.isAfter(now)) ? from : now);

        if (searchStart.isAfter(horizonEnd)) {
            return new NextFreeSlotsResponse(assetId, durationMinutes, true, List.of());
        }

        List<Reservation> activeBookings = reservationRepository.findByAssetIdAndStatusInOrderByStartTimeAsc(
                assetId, ReservationStatus.ACTIVE_STATUSES);
        Long callerId = ReservationServiceSupport.currentUserIdOrNull(currentUser);
        List<ReservationHold> foreignHolds =
                reservationHoldRepository
                        .findByAssetIdAndStatusAndExpiresAtAfter(assetId, HoldStatus.ACTIVE, now)
                        .stream()
                        .filter(h -> !Objects.equals(h.getUserId(), callerId))
                        .toList();

        List<FreeSlotOption> freeSlots = new ArrayList<>();
        LocalDate day = searchStart.toLocalDate();
        LocalDate lastDay = horizonEnd.toLocalDate();
        LocalTime lastStart = CLOSE_TIME.minusMinutes(durationMinutes);

        while (!day.isAfter(lastDay) && freeSlots.size() < limit) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                for (LocalTime t = OPEN_TIME;
                        !t.isAfter(lastStart) && freeSlots.size() < limit;
                        t = t.plusMinutes(STEP_MINUTES)) {
                    LocalDateTime slotStart = LocalDateTime.of(day, t);
                    LocalDateTime slotEnd = slotStart.plusMinutes(durationMinutes);
                    if (slotStart.isBefore(searchStart) || slotStart.isAfter(horizonEnd)) {
                        continue;
                    }
                    if (!overlapsActiveBookings(slotStart, slotEnd, activeBookings)
                            && !overlapsForeignHolds(slotStart, slotEnd, foreignHolds)) {
                        freeSlots.add(new FreeSlotOption(slotStart, slotEnd));
                    }
                }
            }
            day = day.plusDays(1);
        }

        return new NextFreeSlotsResponse(assetId, durationMinutes, true, freeSlots);
    }

    private static LocalDateTime ceilToHalfHour(LocalDateTime dt) {
        LocalDateTime truncated = dt.withSecond(0).withNano(0);
        int minute = truncated.getMinute();
        boolean hasSubMinute = dt.getSecond() > 0 || dt.getNano() > 0;
        if (minute == 0 && !hasSubMinute) {
            return truncated;
        }
        if (minute < 30 || (minute == 30 && !hasSubMinute)) {
            return truncated.withMinute(30);
        }
        return truncated.plusHours(1).withMinute(0);
    }

    private static boolean overlapsActiveBookings(
            LocalDateTime slotStart, LocalDateTime slotEnd, List<Reservation> activeBookings) {
        for (Reservation r : activeBookings) {
            if (r.getStatus() == ReservationStatus.OVERDUE) {
                // OVERDUE bookings block open-endedly from startTime onward until returned (#141).
                if (r.getStartTime().isBefore(slotEnd)) {
                    return true;
                }
            } else if (r.getStartTime().isBefore(slotEnd) && r.getEndTime().isAfter(slotStart)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsForeignHolds(
            LocalDateTime slotStart, LocalDateTime slotEnd, List<ReservationHold> holds) {
        for (ReservationHold h : holds) {
            if (h.getStartTime().isBefore(slotEnd) && h.getEndTime().isAfter(slotStart)) {
                return true;
            }
        }
        return false;
    }
}

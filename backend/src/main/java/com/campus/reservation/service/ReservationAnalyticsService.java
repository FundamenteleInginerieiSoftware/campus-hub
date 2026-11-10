package com.campus.reservation.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.InvalidParameterException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.AssetUtilizationItem;
import com.campus.reservation.web.CapacityBottleneckItem;
import com.campus.reservation.web.HeatmapCell;
import com.campus.reservation.web.HeatmapResponse;
import com.campus.reservation.web.UtilizationAnalyticsResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service calculating equipment utilization metrics, capacity bottlenecks, and booking demand heatmaps
 * from historical and active reservation data.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ReservationAnalyticsService {

    private static final double LAB_DAILY_OPEN_HOURS = 12.0; // 08:00 to 20:00 (Monday-Friday)
    private static final int HEATMAP_START_HOUR = 8;
    private static final int HEATMAP_END_HOUR = 20;

    private static final Set<ReservationStatus> REALIZED_STATUSES = Set.of(
            ReservationStatus.CONFIRMED,
            ReservationStatus.CHECKED_OUT,
            ReservationStatus.OVERDUE,
            ReservationStatus.RETURNED);

    private final ReservationRepository reservationRepository;
    private final CatalogApi catalogApi;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ReservationAnalyticsService(
            ReservationRepository reservationRepository,
            CatalogApi catalogApi,
            Clock clock,
            @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.reservationRepository = reservationRepository;
        this.catalogApi = catalogApi;
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReservationAnalyticsService(
            ReservationRepository reservationRepository, CatalogApi catalogApi, Clock clock) {
        this(reservationRepository, catalogApi, clock, null);
    }

    /**
     * Calculates laboratory and per-asset utilization rates, cancellation/no-show rates,
     * and detects model capacity bottlenecks for a given time window.
     */
    public UtilizationAnalyticsResponse getUtilization(LocalDateTime from, LocalDateTime to) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime resolvedTo = (to != null) ? to : now;
        LocalDateTime resolvedFrom = (from != null) ? from : resolvedTo.minusDays(30);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new InvalidParameterException("from must not be after to");
        }

        int reportingDays =
                Math.max(1, (int) ChronoUnit.DAYS.between(resolvedFrom.toLocalDate(), resolvedTo.toLocalDate()) + 1);

        long openMinutes = 0;
        LocalDate curr = resolvedFrom.toLocalDate();
        LocalDate last = resolvedTo.toLocalDate();
        while (!curr.isAfter(last)) {
            if (curr.getDayOfWeek() != DayOfWeek.SATURDAY && curr.getDayOfWeek() != DayOfWeek.SUNDAY) {
                LocalDateTime dayOpen = curr.atTime(HEATMAP_START_HOUR, 0);
                LocalDateTime dayClose = curr.atTime(HEATMAP_END_HOUR, 0);
                LocalDateTime windowStart = resolvedFrom.isAfter(dayOpen) ? resolvedFrom : dayOpen;
                LocalDateTime windowEnd = resolvedTo.isBefore(dayClose) ? resolvedTo : dayClose;
                if (windowStart.isBefore(windowEnd)) {
                    openMinutes += Duration.between(windowStart, windowEnd).toMinutes();
                }
            }
            curr = curr.plusDays(1);
        }
        double openHoursPerAsset = openMinutes / 60.0;

        List<Reservation> allIntersecting = reservationRepository.findIntersecting(resolvedFrom, resolvedTo);

        // 1. Overall booking outcomes (cancellations and no-shows)
        int totalBookingsCount = allIntersecting.size();
        long cancelledCount = allIntersecting.stream()
                .filter(r -> r.getStatus() == ReservationStatus.CANCELLED
                        || r.getStatus() == ReservationStatus.FORCE_CANCELLED)
                .count();
        long noShowCount = allIntersecting.stream()
                .filter(r -> r.getStatus() == ReservationStatus.NO_SHOW)
                .count();

        double cancellationRate =
                totalBookingsCount > 0 ? round1((double) cancelledCount * 100.0 / totalBookingsCount) : 0.0;
        double noShowRate = totalBookingsCount > 0 ? round1((double) noShowCount * 100.0 / totalBookingsCount) : 0.0;

        // 2. Realized booking hours per asset
        Map<Long, Double> assetBookedHoursMap = new HashMap<>();
        Map<Long, Integer> assetBookingCountMap = new HashMap<>();

        for (Reservation r : allIntersecting) {
            if (REALIZED_STATUSES.contains(r.getStatus())) {
                LocalDateTime start = r.getStartTime().isBefore(resolvedFrom) ? resolvedFrom : r.getStartTime();
                LocalDateTime end = r.getEndTime().isAfter(resolvedTo) ? resolvedTo : r.getEndTime();
                double hours = Math.max(0.0, Duration.between(start, end).toMinutes() / 60.0);

                assetBookedHoursMap.merge(r.getAssetId(), hours, Double::sum);
                assetBookingCountMap.merge(r.getAssetId(), 1, Integer::sum);
            }
        }

        // 3. Asset summaries & utilization breakdown
        // Tracks all operational equipment assets (including never-booked ones)
        Set<Long> allAssetIds = new TreeSet<>();
        if (jdbcTemplate != null) {
            try {
                List<Long> dbAssetIds =
                        jdbcTemplate.queryForList("SELECT id FROM assets WHERE status != 'RETIRED'", Long.class);
                allAssetIds.addAll(dbAssetIds);
            } catch (Exception e) {
                log.warn("Could not query assets table: {}", e.getMessage());
            }
        }
        allAssetIds.addAll(reservationRepository.findDistinctAssetIds());
        List<AssetUtilizationItem> assetItems = new ArrayList<>();
        Map<String, List<Long>> modelToAssetIds = new HashMap<>();

        for (Long assetId : allAssetIds) {
            try {
                AssetSummary asset = catalogApi.getAsset(assetId);
                // Exclude retired equipment from active utilization metrics
                if (asset.status() == com.campus.catalog.api.AssetStatus.RETIRED) {
                    continue;
                }
                double bookedHours = assetBookedHoursMap.getOrDefault(assetId, 0.0);
                int bookingCount = assetBookingCountMap.getOrDefault(assetId, 0);
                double utilPercent = openHoursPerAsset > 0
                        ? Math.min(100.0, round1((bookedHours / openHoursPerAsset) * 100.0))
                        : 0.0;
                boolean isIdle = (bookedHours == 0.0);

                assetItems.add(new AssetUtilizationItem(
                        assetId,
                        asset.name(),
                        asset.model(),
                        asset.categoryId(),
                        round1(bookedHours),
                        utilPercent,
                        bookingCount,
                        asset.status(),
                        isIdle));

                modelToAssetIds
                        .computeIfAbsent(asset.model(), k -> new ArrayList<>())
                        .add(assetId);
            } catch (Exception e) {
                log.warn("Could not retrieve asset #{} from catalog: {}", assetId, e.getMessage());
            }
        }

        assetItems.sort(Comparator.comparing(AssetUtilizationItem::bookedHours).reversed());

        double totalBookedHours = round1(assetBookedHoursMap.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum());
        int totalAssetsTracked = assetItems.size();
        double totalCapacityHours = round1(totalAssetsTracked * openHoursPerAsset);
        double avgUtilization =
                totalCapacityHours > 0 ? Math.min(100.0, round1((totalBookedHours / totalCapacityHours) * 100.0)) : 0.0;
        int idleAssetsCount =
                (int) assetItems.stream().filter(AssetUtilizationItem::idle).count();

        // 4. Capacity bottleneck detection per model
        List<CapacityBottleneckItem> bottlenecks =
                detectBottlenecks(modelToAssetIds, allIntersecting, resolvedFrom, resolvedTo);

        return new UtilizationAnalyticsResponse(
                resolvedFrom,
                resolvedTo,
                reportingDays,
                totalBookedHours,
                totalCapacityHours,
                avgUtilization,
                totalBookingsCount,
                cancellationRate,
                noShowRate,
                totalAssetsTracked,
                idleAssetsCount,
                assetItems,
                bottlenecks);
    }

    /**
     * Builds a 7-day by 12-hour (08:00 to 20:00) demand heatmap grid.
     */
    public HeatmapResponse getHeatmap(LocalDateTime from, LocalDateTime to, Long assetId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime resolvedTo = (to != null) ? to : now;
        LocalDateTime resolvedFrom = (from != null) ? from : resolvedTo.minusDays(30);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new InvalidParameterException("from must not be after to");
        }

        List<Reservation> bookings = (assetId != null)
                ? reservationRepository.findIntersectingByAssetId(assetId, resolvedFrom, resolvedTo)
                : reservationRepository.findIntersecting(resolvedFrom, resolvedTo);

        int[][] grid = new int[8][24];
        int sampledCount = 0;

        for (Reservation r : bookings) {
            if (REALIZED_STATUSES.contains(r.getStatus())) {
                sampledCount++;
                LocalDateTime start = r.getStartTime().isBefore(resolvedFrom) ? resolvedFrom : r.getStartTime();
                LocalDateTime end = r.getEndTime().isAfter(resolvedTo) ? resolvedTo : r.getEndTime();

                // Increment each hourly slot spanned by the reservation
                LocalDateTime cursor = start.truncatedTo(ChronoUnit.HOURS);
                while (cursor.isBefore(end)) {
                    int dayOfWeek = cursor.getDayOfWeek().getValue(); // 1 = Mon ... 7 = Sun
                    int hour = cursor.getHour();
                    if (hour >= HEATMAP_START_HOUR && hour < HEATMAP_END_HOUR) {
                        grid[dayOfWeek][hour]++;
                    }
                    cursor = cursor.plusHours(1);
                }
            }
        }

        int peakDemand = 0;
        for (int d = 1; d <= 7; d++) {
            for (int h = HEATMAP_START_HOUR; h < HEATMAP_END_HOUR; h++) {
                if (grid[d][h] > peakDemand) {
                    peakDemand = grid[d][h];
                }
            }
        }

        List<HeatmapCell> cells = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            for (int h = HEATMAP_START_HOUR; h < HEATMAP_END_HOUR; h++) {
                int count = grid[d][h];
                double intensity = peakDemand > 0 ? round2((double) count / peakDemand) : 0.0;
                cells.add(new HeatmapCell(d, h, count, intensity));
            }
        }

        return new HeatmapResponse(resolvedFrom, resolvedTo, assetId, sampledCount, peakDemand, cells);
    }

    private List<CapacityBottleneckItem> detectBottlenecks(
            Map<String, List<Long>> modelToAssetIds,
            List<Reservation> allBookings,
            LocalDateTime from,
            LocalDateTime to) {

        List<CapacityBottleneckItem> bottlenecks = new ArrayList<>();

        for (Map.Entry<String, List<Long>> entry : modelToAssetIds.entrySet()) {
            String model = entry.getKey();
            List<Long> assetIds = entry.getValue();
            int unitCount = assetIds.size();
            if (unitCount == 0) {
                continue;
            }

            Set<Long> assetIdSet = new TreeSet<>(assetIds);
            List<Reservation> modelBookings = allBookings.stream()
                    .filter(r -> REALIZED_STATUSES.contains(r.getStatus()))
                    .filter(r -> assetIdSet.contains(r.getAssetId()))
                    .toList();

            if (modelBookings.isEmpty()) {
                continue;
            }

            // Sweep-line algorithm over booking boundaries
            List<TimePoint> points = new ArrayList<>();
            for (Reservation r : modelBookings) {
                LocalDateTime start = r.getStartTime().isBefore(from) ? from : r.getStartTime();
                LocalDateTime end = r.getEndTime().isAfter(to) ? to : r.getEndTime();
                points.add(new TimePoint(start, 1));
                points.add(new TimePoint(end, -1));
            }

            points.sort(Comparator.comparing(TimePoint::time).thenComparingInt(TimePoint::delta));

            int currentDemand = 0;
            int peakDemand = 0;
            int saturatedPeriodsCount = 0;
            boolean inSaturation = false;

            for (TimePoint pt : points) {
                currentDemand += pt.delta();
                if (currentDemand > peakDemand) {
                    peakDemand = currentDemand;
                }
                if (currentDemand >= unitCount) {
                    if (!inSaturation) {
                        saturatedPeriodsCount++;
                        inSaturation = true;
                    }
                } else {
                    inSaturation = false;
                }
            }

            if (peakDemand >= unitCount) {
                double peakRate = round1((double) peakDemand / unitCount * 100.0);
                String recommendation;
                if (peakDemand > unitCount) {
                    int deficit = peakDemand - unitCount;
                    recommendation = String.format(
                            "Peak concurrent demand exceeded available units (%d requested vs %d units, %.0f%% capacity). Recommend acquiring %d additional unit(s) to eliminate capacity shortages.",
                            peakDemand, unitCount, peakRate, deficit);
                } else {
                    recommendation = String.format(
                            "Peak concurrent demand reached 100%% capacity (%d of %d available units). Operating at full capacity during peak periods; monitor demand before expanding inventory.",
                            peakDemand, unitCount);
                }

                bottlenecks.add(new CapacityBottleneckItem(
                        model, unitCount, peakDemand, saturatedPeriodsCount, peakRate, recommendation));
            }
        }

        bottlenecks.sort(Comparator.comparing(CapacityBottleneckItem::peakConcurrentDemand)
                .reversed());
        return bottlenecks;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record TimePoint(LocalDateTime time, int delta) {}
}

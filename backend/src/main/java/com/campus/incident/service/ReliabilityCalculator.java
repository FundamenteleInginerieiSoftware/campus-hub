package com.campus.incident.service;

import com.campus.incident.web.AssetReliabilityItem;
import com.campus.incident.web.ReliabilityLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks the units that keep breaking (#291, spec {@code docs/superpowers/specs/2026-11-10-equipment-reliability-design.md}).
 *
 * <p>Pure: lists in, list out. No Spring, no clock, no database, so every rule is tested with numbers
 * worked out by hand. Levels and ratios are decided on the unrounded values; only the published numbers
 * are rounded to one decimal.
 */
public final class ReliabilityCalculator {

    /** Below 10 hours of use we calculate as if it were 10, so one incident after 1 hour is not "100 per 100 h". */
    static final double MIN_HOURS = 10.0;

    /** Three real failures in the window are enough on their own, whatever the twins do. */
    static final int INSPECT_FAILURES = 3;

    /** The ratio rule needs at least 2 failures, so one unlucky incident never marks a unit INSPECT. */
    static final int LEMON_FAILURES = 2;

    /** A lemon fails at least twice as often as the other units of the same model. */
    static final double LEMON_RATIO = 2.0;

    private ReliabilityCalculator() {}

    /**
     * @param units every unit that had use or incidents in the window (units without failures still count
     *     for their model's comparison, but are not listed)
     * @param incidents incidents created in the window; incidents on a unit not in {@code units} are ignored
     * @param windowDays length of the window, only used in the sentence
     * @return units with at least one failure: INSPECT first, then by failures per 100 h (highest first),
     *     then by asset id
     */
    public static List<AssetReliabilityItem> rank(
            List<UnitInput> units, List<IncidentInput> incidents, int windowDays) {
        Map<Long, List<IncidentInput>> failuresByAsset = new HashMap<>();
        for (IncidentInput incident : incidents) {
            if (!incident.rejected()) {
                failuresByAsset
                        .computeIfAbsent(incident.assetId(), id -> new ArrayList<>())
                        .add(incident);
            }
        }

        Map<String, ModelTotals> totalsByModel = new HashMap<>();
        for (UnitInput unit : units) {
            ModelTotals totals = totalsByModel.computeIfAbsent(unit.model(), m -> new ModelTotals());
            totals.failures +=
                    failuresByAsset.getOrDefault(unit.assetId(), List.of()).size();
            totals.hours += hours(unit);
        }

        List<Row> rows = new ArrayList<>();
        for (UnitInput unit : units) {
            List<IncidentInput> failures = failuresByAsset.getOrDefault(unit.assetId(), List.of());
            if (!failures.isEmpty()) {
                rows.add(row(unit, failures, totalsByModel.get(unit.model()), windowDays));
            }
        }

        rows.sort(Comparator.comparing((Row r) -> r.item.level())
                .thenComparing(Comparator.comparingDouble((Row r) -> r.rate).reversed())
                .thenComparing(r -> r.item.assetId()));
        return rows.stream().map(r -> r.item).toList();
    }

    private static Row row(UnitInput unit, List<IncidentInput> failures, ModelTotals model, int windowDays) {
        int failureCount = failures.size();
        int severe = (int) failures.stream().filter(IncidentInput::severe).count();
        double hours = hours(unit);
        double rate = per100h(failureCount, hours);
        Double mtbf = hours > 0 ? hours / failureCount : null;

        // The unit itself is left out of its model's baseline, otherwise a lemon would hide inside its own average.
        double othersHours = model.hours - hours;
        int othersFailures = model.failures - failureCount;
        Double vsModel = null;
        if (othersHours >= MIN_HOURS && othersFailures > 0) {
            vsModel = rate / per100h(othersFailures, othersHours);
        }

        boolean lemon = vsModel != null && vsModel >= LEMON_RATIO;
        ReliabilityLevel level = failureCount >= INSPECT_FAILURES || (failureCount >= LEMON_FAILURES && lemon)
                ? ReliabilityLevel.INSPECT
                : ReliabilityLevel.WATCH;

        StringBuilder reason = new StringBuilder()
                .append(failureCount)
                .append(failureCount == 1 ? " incident" : " incidents")
                .append(" in the last ")
                .append(windowDays)
                .append(" days");
        if (lemon) {
            reason.append("; fails ")
                    .append(round(vsModel).toPlainString())
                    .append("× more often than other ")
                    .append(unit.model())
                    .append(" units");
        }

        AssetReliabilityItem item = new AssetReliabilityItem(
                unit.assetId(),
                unit.name(),
                unit.model(),
                unit.status(),
                failureCount,
                severe,
                round(hours),
                round(rate),
                round(mtbf),
                round(averageRepairHours(failures)),
                round(vsModel),
                level,
                reason.toString());
        return new Row(item, rate);
    }

    private static Double averageRepairHours(List<IncidentInput> failures) {
        List<IncidentInput> resolved = failures.stream()
                .filter(i -> i.resolvedAt() != null && i.createdAt() != null)
                .toList();
        if (resolved.isEmpty()) {
            return null;
        }
        double totalMinutes = resolved.stream()
                .mapToLong(i -> Duration.between(i.createdAt(), i.resolvedAt()).toMinutes())
                .sum();
        return totalMinutes / 60.0 / resolved.size();
    }

    private static double hours(UnitInput unit) {
        return unit.usedMinutes() / 60.0;
    }

    private static double per100h(int failures, double hours) {
        return failures * 100.0 / Math.max(hours, MIN_HOURS);
    }

    private static BigDecimal round(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static final class ModelTotals {
        private int failures;
        private double hours;
    }

    /** The unrounded rate is kept next to the row so the sort does not depend on the rounding. */
    private record Row(AssetReliabilityItem item, double rate) {}
}

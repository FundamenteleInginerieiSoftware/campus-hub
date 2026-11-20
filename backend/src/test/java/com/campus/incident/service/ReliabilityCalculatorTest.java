package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.incident.web.AssetReliabilityItem;
import com.campus.incident.web.ReliabilityLevel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReliabilityCalculatorTest {

    private static final int WINDOW = 180;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final String SCOPE = "DS1054Z";
    private static final String METER = "Fluke 115";

    @Test
    void workedExampleFromTheSpec() {
        List<UnitInput> units =
                List.of(unit(1, SCOPE, hours(60)), unit(2, SCOPE, hours(40)), unit(3, SCOPE, hours(60)));
        List<IncidentInput> incidents = List.of(failure(2), failure(2), rejected(2), failure(1));

        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(units, incidents, WINDOW);

        assertThat(rows)
                .as("#2 INSPECT, #1 WATCH, #3 had no failure; see ReliabilityCalculator.rank")
                .extracting(AssetReliabilityItem::assetId)
                .containsExactly(2L, 1L);
        AssetReliabilityItem lemon = rows.get(0);
        // 2 failures (the rejected one does not count), 40 h used
        assertThat(lemon.failures()).as("rejected report is not a failure").isEqualTo(2);
        assertThat(lemon.usageHours()).as("2400 min / 60").isEqualTo(bd("40.0"));
        // 2 / 40 × 100 = 5.0
        assertThat(lemon.failuresPer100h()).as("failures per 100 h").isEqualTo(bd("5.0"));
        // twins #1 + #3: 1 failure in 120 h → 0.833…; 5.0 / 0.833… = 6.0
        assertThat(lemon.vsModel()).as("rate vs the other units of the model").isEqualTo(bd("6.0"));
        assertThat(lemon.level()).as("2 failures and ratio ≥ 2.0").isEqualTo(ReliabilityLevel.INSPECT);
        assertThat(lemon.reason())
                .as("the sentence of spec §5.4")
                .isEqualTo("2 incidents in the last 180 days; fails 6.0× more often than other DS1054Z units");
        assertThat(rows.get(1).level()).as("1 failure is WATCH").isEqualTo(ReliabilityLevel.WATCH);
    }

    @Test
    void onlyRejectedReportsMeansNotListed() {
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(20))), List.of(rejected(1), rejected(1)), WINDOW);

        assertThat(rows).as("a rejected report means the unit worked").isEmpty();
    }

    @Test
    void threeFailuresIsInspectWithoutAnyRatio() {
        AssetReliabilityItem row =
                only(List.of(unit(1, SCOPE, hours(100))), List.of(failure(1), failure(1), failure(1)));

        assertThat(row.vsModel()).as("only unit of its model").isNull();
        assertThat(row.level()).as("failures ≥ INSPECT_FAILURES").isEqualTo(ReliabilityLevel.INSPECT);
        assertThat(row.reason()).as("no ratio, no 'fails …' part").isEqualTo("3 incidents in the last 180 days");
    }

    @Test
    void twoFailuresWithoutRatioIsWatch() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, hours(100))), List.of(failure(1), failure(1)));

        assertThat(row.level()).as("2 failures need the ratio to be INSPECT").isEqualTo(ReliabilityLevel.WATCH);
    }

    @Test
    void twoFailuresAtExactlyDoubleTheModelRateIsInspect() {
        // unit: 2 / 100 h × 100 = 2.0; twin: 1 / 100 h × 100 = 1.0 → ratio 2.0
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(100)), unit(2, SCOPE, hours(100))),
                List.of(failure(1), failure(1), failure(2)),
                WINDOW);

        AssetReliabilityItem row = byId(rows, 1);
        assertThat(row.vsModel()).as("exactly double").isEqualTo(bd("2.0"));
        assertThat(row.level()).as("ratio ≥ LEMON_RATIO is inclusive").isEqualTo(ReliabilityLevel.INSPECT);
    }

    @Test
    void twoFailuresJustBelowDoubleIsWatch() {
        // unit: 2 / 100 h × 100 = 2.0; twin: 1 / 99 h × 100 = 1.0101… → ratio 1.98
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(100)), unit(2, SCOPE, hours(99))),
                List.of(failure(1), failure(1), failure(2)),
                WINDOW);

        AssetReliabilityItem row = byId(rows, 1);
        assertThat(row.vsModel()).as("1.98 published as 2.0").isEqualTo(bd("2.0"));
        assertThat(row.level()).as("the level is decided on the unrounded 1.98").isEqualTo(ReliabilityLevel.WATCH);
        assertThat(row.reason())
                .as("below 2.0 there is no 'fails …' part")
                .isEqualTo("2 incidents in the last 180 days");
    }

    @Test
    void oneFailureWithAHugeRatioIsStillWatch() {
        // unit: 1 / 10 h × 100 = 10.0; twin: 1 / 1000 h × 100 = 0.1 → ratio 100.0
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(10)), unit(2, SCOPE, hours(1000))),
                List.of(failure(1), failure(2)),
                WINDOW);

        AssetReliabilityItem row = byId(rows, 1);
        assertThat(row.vsModel()).as("ratio").isEqualTo(bd("100.0"));
        assertThat(row.level()).as("the ratio rule needs LEMON_FAILURES").isEqualTo(ReliabilityLevel.WATCH);
    }

    @Test
    void lowUseIsCalculatedOnTenHours() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, 60)), List.of(failure(1)));

        assertThat(row.usageHours()).as("60 min = 1.0 h, published as it is").isEqualTo(bd("1.0"));
        // 1 / max(1, 10) × 100 = 10.0, not 100.0
        assertThat(row.failuresPer100h()).as("MIN_HOURS floor").isEqualTo(bd("10.0"));
    }

    @Test
    void zeroUseUsesTheFloorAndHasNoMtbf() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, 0)), List.of(failure(1)));

        // 1 / max(0, 10) × 100 = 10.0
        assertThat(row.failuresPer100h())
                .as("MIN_HOURS floor, no division by zero")
                .isEqualTo(bd("10.0"));
        assertThat(row.mtbfHours()).as("no hours of use, no MTBF").isNull();
    }

    @Test
    void mtbfIsHoursPerFailure() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, hours(50))), List.of(failure(1), failure(1)));

        // 50 h / 2 failures
        assertThat(row.mtbfHours()).as("usageHours / failures").isEqualTo(bd("25.0"));
    }

    @Test
    void avgRepairUsesOnlyResolvedIncidents() {
        AssetReliabilityItem row =
                only(List.of(unit(1, SCOPE, hours(50))), List.of(resolved(1, T0, 4), resolved(1, T0, 8), failure(1)));

        // (4 + 8) / 2; the open one has no repair time yet
        assertThat(row.avgRepairHours()).as("average over RESOLVED only").isEqualTo(bd("6.0"));
    }

    @Test
    void avgRepairIsEmptyWhenNothingWasResolved() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, hours(50))), List.of(failure(1)));

        assertThat(row.avgRepairHours()).as("no RESOLVED incident").isNull();
    }

    @Test
    void severeFailuresAreCountedSeparately() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, hours(50))), List.of(severe(1), failure(1)));

        assertThat(row.failures()).as("severe ones are failures too").isEqualTo(2);
        assertThat(row.severeFailures()).as("HIGH/CRITICAL only").isEqualTo(1);
    }

    @Test
    void noTwinsMeansNoRatio() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, hours(50))), List.of(failure(1), failure(1)));

        assertThat(row.vsModel()).as("only unit of its model").isNull();
    }

    @Test
    void twinsWithoutFailuresMeanNoRatio() {
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(50)), unit(2, SCOPE, hours(200))),
                List.of(failure(1), failure(1)),
                WINDOW);

        assertThat(rows).as("the healthy twin is not listed").hasSize(1);
        assertThat(rows.get(0).vsModel())
                .as("twins rate 0, no division by zero")
                .isNull();
        assertThat(rows.get(0).level()).as("count rule alone").isEqualTo(ReliabilityLevel.WATCH);
    }

    @Test
    void twinsWithLessThanTenHoursTogetherAreIgnored() {
        // twins: 4 h + 5 h = 9 h < MIN_HOURS
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(50)), unit(2, SCOPE, hours(4)), unit(3, SCOPE, hours(5))),
                List.of(failure(1), failure(1), failure(2)),
                WINDOW);

        assertThat(byId(rows, 1).vsModel()).as("too little twin data").isNull();
    }

    @Test
    void comparisonIsPerModel() {
        // scope #1: 2 / 100 h → 2.0; its twin #2: 1 / 100 h → 1.0; the meter's failures must not count
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(100)), unit(2, SCOPE, hours(100)), unit(3, METER, hours(100))),
                List.of(failure(1), failure(1), failure(2), failure(3), failure(3), failure(3)),
                WINDOW);

        assertThat(byId(rows, 1).vsModel())
                .as("only DS1054Z twins in the baseline")
                .isEqualTo(bd("2.0"));
        assertThat(byId(rows, 3).vsModel()).as("the only Fluke 115").isNull();
    }

    @Test
    void retiredUnitIsListedAndCountsForItsModel() {
        // unit #1: 2 / 50 h → 4.0; retired twin #2: 1 / 100 h → 1.0 → ratio 4.0
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, SCOPE, hours(50)), new UnitInput(2L, "Scope #2", SCOPE, "RETIRED", hours(100))),
                List.of(failure(1), failure(1), failure(2)),
                WINDOW);

        assertThat(byId(rows, 2).status())
                .as("the admin sees it is already out")
                .isEqualTo("RETIRED");
        assertThat(byId(rows, 1).vsModel())
                .as("the retired twin is in the baseline")
                .isEqualTo(bd("4.0"));
    }

    @Test
    void sortsByLevelThenRateThenId() {
        List<IncidentInput> incidents = new ArrayList<>();
        // WATCH: 2 / max(5, 10) × 100 = 20.0, no twins
        incidents.addAll(List.of(failure(7), failure(7)));
        // two INSPECT: 3 / 60 h × 100 = 5.0 each
        incidents.addAll(List.of(failure(9), failure(9), failure(9), failure(4), failure(4), failure(4)));

        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(7, "A", hours(5)), unit(9, "B", hours(60)), unit(4, "C", hours(60))), incidents, WINDOW);

        assertThat(rows)
                .as("level wins over a higher rate; equal rates by id")
                .extracting(AssetReliabilityItem::assetId)
                .containsExactly(4L, 9L, 7L);
    }

    @Test
    void higherRateComesFirstInsideALevel() {
        // #1: 1 / 100 h → 1.0; #2: 1 / 20 h → 5.0; different models, so no ratio
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(
                List.of(unit(1, "A", hours(100)), unit(2, "B", hours(20))), List.of(failure(1), failure(2)), WINDOW);

        assertThat(rows)
                .as("failuresPer100h descending")
                .extracting(AssetReliabilityItem::assetId)
                .containsExactly(2L, 1L);
    }

    @Test
    void singularSentenceForOneIncident() {
        List<AssetReliabilityItem> rows =
                ReliabilityCalculator.rank(List.of(unit(1, SCOPE, hours(50))), List.of(failure(1)), 90);

        assertThat(rows.get(0).reason()).as("singular + the window").isEqualTo("1 incident in the last 90 days");
    }

    @Test
    void publishedNumbersHaveOneDecimal() {
        AssetReliabilityItem row = only(List.of(unit(1, SCOPE, hours(70))), List.of(failure(1)));

        // 1 / 70 × 100 = 1.428… → 1.4
        assertThat(row.failuresPer100h()).as("HALF_UP to one decimal").isEqualTo(bd("1.4"));
        assertThat(row.failuresPer100h().scale()).as("scale 1").isEqualTo(1);
    }

    @Test
    void incidentsOnUnknownUnitsAreIgnored() {
        List<AssetReliabilityItem> rows =
                ReliabilityCalculator.rank(List.of(unit(1, SCOPE, hours(50))), List.of(failure(99)), WINDOW);

        assertThat(rows)
                .as("the service skips assets the catalog no longer knows")
                .isEmpty();
    }

    private static AssetReliabilityItem only(List<UnitInput> units, List<IncidentInput> incidents) {
        List<AssetReliabilityItem> rows = ReliabilityCalculator.rank(units, incidents, WINDOW);
        assertThat(rows).as("exactly one listed unit").hasSize(1);
        return rows.get(0);
    }

    private static AssetReliabilityItem byId(List<AssetReliabilityItem> rows, long id) {
        return rows.stream().filter(r -> r.assetId() == id).findFirst().orElseThrow();
    }

    private static UnitInput unit(long id, String model, long minutes) {
        return new UnitInput(id, model + " #" + id, model, "AVAILABLE", minutes);
    }

    private static long hours(long h) {
        return h * 60;
    }

    private static IncidentInput failure(long assetId) {
        return new IncidentInput(assetId, false, false, T0, null);
    }

    private static IncidentInput severe(long assetId) {
        return new IncidentInput(assetId, false, true, T0, null);
    }

    private static IncidentInput rejected(long assetId) {
        return new IncidentInput(assetId, true, false, T0, T0.plusHours(1));
    }

    private static IncidentInput resolved(long assetId, LocalDateTime created, long hoursToFix) {
        return new IncidentInput(assetId, false, false, created, created.plusHours(hoursToFix));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

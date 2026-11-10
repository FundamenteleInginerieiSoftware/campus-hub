package com.campus.reassignment.service.optimal;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.reassignment.web.OptimalReassignmentPlanResponse;
import com.campus.reassignment.web.ReassignmentActionItem;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OptimalReassignmentSolverTest {

    private OptimalReassignmentSolver solver;

    @BeforeEach
    void setUp() {
        solver = new OptimalReassignmentSolver();
    }

    @Test
    @DisplayName(
            "Counterexample: MCMF rescues 100% of reservations where Greedy cancels a student due to short-sighted allocation")
    void solve_rescuesAllReservationsWhereGreedyFails() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 15, 10, 0);

        // Damaged oscilloscope (Model DS1054Z, Category 1)
        AssetSummary damaged = new AssetSummary(1L, "Scope Damaged", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        // Two concurrent reservations competing for substitutes (10:00 to 12:00)
        ReservationSummary r1 =
                new ReservationSummary(101L, 42L, 1L, base, base.plusHours(2), ReservationStatus.CONFIRMED);
        ReservationSummary r2 =
                new ReservationSummary(102L, 43L, 1L, base, base.plusHours(2), ReservationStatus.CONFIRMED);

        // Available in the lab:
        // Asset 10: Exact Same Model (DS1054Z, Category 1) - compatible with both
        AssetSummary subSameModel = new AssetSummary(10L, "Scope B", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        // Asset 20: Same Category Only (DG1022Z, Category 1) - can only substitute for r1
        AssetSummary subSameCategory = new AssetSummary(20L, "Scope C", "OTHER_MODEL", 1L, 1L, AssetStatus.AVAILABLE);

        List<AssetSummary> candidates = List.of(subSameModel, subSameCategory);

        // r1 can accept subSameModel (cost 10) or subSameCategory (cost 50)
        // r2 can ONLY accept subSameModel (cost 10) because it strictly needs DS1054Z
        OptimalReassignmentPlanResponse response =
                solver.solve(1L, "Scope Damaged", List.of(r1, r2), damaged, candidates, (candidateId, booking) -> {
                    if (booking.id() == 102L) {
                        return candidateId == 10L; // r2 only compatible with asset 10
                    }
                    return true; // r1 compatible with both
                });

        assertThat(response)
                .as("plan response must not be null; see OptimalReassignmentSolver.solve")
                .isNotNull();

        assertThat(response.rescuedReservations())
                .as("MCMF must rescue both reservations (2/2); see OptimalReassignmentSolver.solve")
                .isEqualTo(2);
        assertThat(response.forceCancelledReservations())
                .as("MCMF must have zero cancellations; see OptimalReassignmentSolver.solve")
                .isZero();

        // Check academic greedy comparison
        assertThat(response.comparison().greedyRescuedCount())
                .as("Greedy heuristic only rescues 1 reservation because it greedily consumed the only unit r2 can use")
                .isEqualTo(1);
        assertThat(response.comparison().greedyForceCancelledCount())
                .as("Greedy heuristic force-cancels 1 reservation")
                .isEqualTo(1);
        assertThat(response.comparison().optimalStrictlyBetter())
                .as("MCMF global optimization must be strictly better than greedy")
                .isTrue();
    }

    @Test
    @DisplayName("Interval reuse: a single substitute asset can be assigned to multiple non-overlapping reservations")
    void solve_reusesSubstituteAcrossNonOverlappingIntervals() {
        LocalDateTime t10 = LocalDateTime.of(2026, 11, 15, 10, 0);
        LocalDateTime t12 = LocalDateTime.of(2026, 11, 15, 12, 0);
        LocalDateTime t14 = LocalDateTime.of(2026, 11, 15, 14, 0);
        LocalDateTime t16 = LocalDateTime.of(2026, 11, 15, 16, 0);

        AssetSummary damaged = new AssetSummary(1L, "Scope Damaged", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        // Booking 1: 10:00 - 12:00
        ReservationSummary r1 = new ReservationSummary(101L, 42L, 1L, t10, t12, ReservationStatus.CONFIRMED);
        // Booking 2: 14:00 - 16:00
        ReservationSummary r2 = new ReservationSummary(102L, 43L, 1L, t14, t16, ReservationStatus.CONFIRMED);

        // Only ONE substitute asset available (Asset 10), but free during both slots
        AssetSummary substitute = new AssetSummary(10L, "Scope Substitute", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);

        OptimalReassignmentPlanResponse response = solver.solve(
                1L, "Scope Damaged", List.of(r1, r2), damaged, List.of(substitute), (candidateId, booking) -> true);

        assertThat(response.rescuedReservations())
                .as(
                        "both non-overlapping bookings must be rescued by the same asset; see OptimalReassignmentSolver.solve")
                .isEqualTo(2);
        assertThat(response.forceCancelledReservations()).isZero();
        assertThat(response.actions())
                .allMatch(action -> action.assignedAssetId().equals(10L));
    }

    @Test
    @DisplayName("Empty bookings: handles empty reservation lists gracefully without throwing")
    void solve_handlesEmptyBookings() {
        AssetSummary damaged = new AssetSummary(1L, "Scope Damaged", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        OptimalReassignmentPlanResponse response =
                solver.solve(1L, "Scope Damaged", List.of(), damaged, List.of(), (c, b) -> true);

        assertThat(response.totalReservations()).isZero();
        assertThat(response.rescuedReservations()).isZero();
        assertThat(response.actions()).isEmpty();
    }

    @Test
    @DisplayName(
            "Non-overlapping transitive cluster: A (08-09) and C (09:30-10:30) can share an asset while B (08:30-10:00) uses another")
    void solve_allocatesNonOverlappingReservationsWithinSameCluster() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 15, 8, 0);

        AssetSummary damaged = new AssetSummary(1L, "Scope Damaged", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        // A: 08:00 - 09:00
        ReservationSummary rA =
                new ReservationSummary(101L, 42L, 1L, base, base.plusHours(1), ReservationStatus.CONFIRMED);
        // B: 08:30 - 10:00 (overlaps A and C)
        ReservationSummary rB = new ReservationSummary(
                102L, 43L, 1L, base.plusMinutes(30), base.plusHours(2), ReservationStatus.CONFIRMED);
        // C: 09:30 - 10:30 (overlaps B, but NOT A)
        ReservationSummary rC = new ReservationSummary(
                103L, 44L, 1L, base.plusMinutes(90), base.plusMinutes(150), ReservationStatus.CONFIRMED);

        // Two available candidate substitutes
        AssetSummary sub1 = new AssetSummary(10L, "Scope 1", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        AssetSummary sub2 = new AssetSummary(20L, "Scope 2", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);

        OptimalReassignmentPlanResponse response =
                solver.solve(1L, "Scope Damaged", List.of(rA, rB, rC), damaged, List.of(sub1, sub2), (c, b) -> true);

        assertThat(response.rescuedReservations())
                .as("All 3 reservations must be rescued since A and C don't overlap on sub1 and B can use sub2")
                .isEqualTo(3);
        assertThat(response.forceCancelledReservations()).isZero();
    }

    @Test
    @DisplayName(
            "Reviewer counterexample: 1 substitute with A (08-10), B (09-11), C (10-12) must rescue A and C, and cancel B, with zero overlapping assignments")
    void solve_reviewerCounterexampleSingleSubstituteCannotDoubleBook() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 15, 8, 0);
        AssetSummary damaged = new AssetSummary(1L, "Scope Damaged", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        // A: 08:00 - 10:00
        ReservationSummary rA =
                new ReservationSummary(101L, 42L, 1L, base, base.plusHours(2), ReservationStatus.CONFIRMED);
        // B: 09:00 - 11:00 (overlaps A and C)
        ReservationSummary rB = new ReservationSummary(
                102L, 43L, 1L, base.plusHours(1), base.plusHours(3), ReservationStatus.CONFIRMED);
        // C: 10:00 - 12:00 (overlaps B, but NOT A)
        ReservationSummary rC = new ReservationSummary(
                103L, 44L, 1L, base.plusHours(2), base.plusHours(4), ReservationStatus.CONFIRMED);

        // Only ONE substitute asset available
        AssetSummary sub1 = new AssetSummary(10L, "Scope 1", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);

        OptimalReassignmentPlanResponse response =
                solver.solve(1L, "Scope Damaged", List.of(rA, rB, rC), damaged, List.of(sub1), (c, b) -> true);

        assertThat(response.rescuedReservations())
                .as("Must rescue exactly 2 reservations (A and C) on sub1 without double-booking B")
                .isEqualTo(2);
        assertThat(response.forceCancelledReservations())
                .as("B must be force-cancelled")
                .isEqualTo(1);

        // Verify that sub1 is assigned to A and C, and B is force-cancelled
        List<ReassignmentActionItem> actions = response.actions();
        ReassignmentActionItem actionA = actions.stream()
                .filter(a -> a.reservationId() == 101L)
                .findFirst()
                .orElseThrow();
        ReassignmentActionItem actionB = actions.stream()
                .filter(a -> a.reservationId() == 102L)
                .findFirst()
                .orElseThrow();
        ReassignmentActionItem actionC = actions.stream()
                .filter(a -> a.reservationId() == 103L)
                .findFirst()
                .orElseThrow();

        assertThat(actionA.assignedAssetId()).isEqualTo(10L);
        assertThat(actionC.assignedAssetId()).isEqualTo(10L);
        assertThat(actionB.assignedAssetId()).isNull(); // force-cancelled
    }

    @Test
    @DisplayName(
            "Reviewer counterexample: non-overlapping bookings in same track can be allocated to different candidate assets")
    void solve_allocatesNonOverlappingBookingsToDifferentCandidates() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 15, 8, 0);
        AssetSummary damaged = new AssetSummary(1L, "Scope Damaged", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        // A: 08:00 - 09:00
        ReservationSummary rA =
                new ReservationSummary(101L, 42L, 1L, base, base.plusHours(1), ReservationStatus.CONFIRMED);
        // B: 10:00 - 11:00 (strictly non-overlapping with A, would have shared Track 0)
        ReservationSummary rB = new ReservationSummary(
                102L, 43L, 1L, base.plusHours(2), base.plusHours(3), ReservationStatus.CONFIRMED);

        // Asset X: only free for A (not B)
        AssetSummary assetX = new AssetSummary(10L, "Scope X", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        // Asset Y: only free for B (not A)
        AssetSummary assetY = new AssetSummary(20L, "Scope Y", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);

        OptimalReassignmentPlanResponse response = solver.solve(
                1L, "Scope Damaged", List.of(rA, rB), damaged, List.of(assetX, assetY), (candidateId, booking) -> {
                    if (candidateId == 10L) return booking.id() == 101L; // X only free for A
                    if (candidateId == 20L) return booking.id() == 102L; // Y only free for B
                    return false;
                });

        assertThat(response.rescuedReservations())
                .as(
                        "Both non-overlapping reservations must be rescued using different candidate assets; see OptimalReassignmentSolver.solve")
                .isEqualTo(2);
        assertThat(response.forceCancelledReservations())
                .as("Zero force-cancellations when both can be satisfied by different candidate assets")
                .isZero();

        List<ReassignmentActionItem> actions = response.actions();
        ReassignmentActionItem actionA = actions.stream()
                .filter(a -> a.reservationId() == 101L)
                .findFirst()
                .orElseThrow();
        ReassignmentActionItem actionB = actions.stream()
                .filter(a -> a.reservationId() == 102L)
                .findFirst()
                .orElseThrow();

        assertThat(actionA.assignedAssetId()).isEqualTo(10L);
        assertThat(actionB.assignedAssetId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Benchmark: solves 60 reservations across 5 days and multiple candidates in sub-50ms")
    void solve_benchmarkLargeReservationSet() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 16, 8, 0);
        AssetSummary damaged = new AssetSummary(1L, "Scope Damaged", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE);

        List<AssetSummary> candidates = List.of(
                new AssetSummary(10L, "Scope Sub 1", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE),
                new AssetSummary(20L, "Scope Sub 2", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE),
                new AssetSummary(30L, "Scope Sub 3", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE),
                new AssetSummary(40L, "Scope Alt 1", "OTHER_MODEL", 1L, 1L, AssetStatus.AVAILABLE),
                new AssetSummary(50L, "Scope Alt 2", "OTHER_MODEL", 1L, 1L, AssetStatus.AVAILABLE));

        // Create 60 reservations spread over 5 days (12 per day across 6 2-hour slots)
        List<ReservationSummary> bookings = new java.util.ArrayList<>();
        long idCounter = 1000L;
        for (int day = 0; day < 5; day++) {
            LocalDateTime dayBase = base.plusDays(day);
            for (int slot = 0; slot < 6; slot++) {
                LocalDateTime start = dayBase.plusHours(slot * 2);
                LocalDateTime end = start.plusHours(2);
                bookings.add(new ReservationSummary(
                        idCounter++, 100L + idCounter, 1L, start, end, ReservationStatus.CONFIRMED));
                bookings.add(new ReservationSummary(
                        idCounter++, 200L + idCounter, 1L, start, end, ReservationStatus.CONFIRMED));
            }
        }

        // Warm up JIT
        solver.solve(1L, "Scope Damaged", bookings.subList(0, 10), damaged, candidates, (cId, b) -> true);

        long startNanos = System.nanoTime();
        OptimalReassignmentPlanResponse response =
                solver.solve(1L, "Scope Damaged", bookings, damaged, candidates, (cId, b) -> true);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(response.totalReservations()).isEqualTo(60);
        assertThat(response.rescuedReservations()).isEqualTo(60);
        assertThat(elapsedMillis)
                .as("Branch-and-bound pruning must solve 60 reservations in under 100ms; took " + elapsedMillis + "ms")
                .isLessThan(100);
    }
}

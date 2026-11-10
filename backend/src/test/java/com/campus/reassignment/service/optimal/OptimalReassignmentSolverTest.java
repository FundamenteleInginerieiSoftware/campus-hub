package com.campus.reassignment.service.optimal;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.reassignment.web.OptimalReassignmentPlanResponse;
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
}

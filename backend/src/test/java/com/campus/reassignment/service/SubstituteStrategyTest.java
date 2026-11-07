package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubstituteStrategyTest {

    @Test
    @DisplayName("default findSubstitute returns first candidate from findCandidates or empty if list is empty")
    void defaultFindSubstituteBehavior() {
        AssetSummary damaged = new AssetSummary(1L, "Asset 1", "M1", 10L, 100L, AssetStatus.UNDER_MAINTENANCE);
        AssetSummary candidate1 = new AssetSummary(2L, "Asset 2", "M1", 10L, 100L, AssetStatus.AVAILABLE);
        AssetSummary candidate2 = new AssetSummary(3L, "Asset 3", "M1", 10L, 100L, AssetStatus.AVAILABLE);
        ReservationSummary booking = new ReservationSummary(
                10L,
                1L,
                1L,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                ReservationStatus.CONFIRMED);

        SubstituteStrategy strategyWithCandidates = new SubstituteStrategy() {
            @Override
            public String name() {
                return "TestStrategy";
            }

            @Override
            public List<AssetSummary> findCandidates(ReservationSummary reservation, AssetSummary damaged) {
                return List.of(candidate1, candidate2);
            }
        };

        assertThat(strategyWithCandidates.name()).isEqualTo("TestStrategy");
        Optional<AssetSummary> first = strategyWithCandidates.findSubstitute(booking, damaged);
        assertThat(first).contains(candidate1);

        SubstituteStrategy emptyStrategy = new SubstituteStrategy() {
            @Override
            public String name() {
                return "EmptyStrategy";
            }

            @Override
            public List<AssetSummary> findCandidates(ReservationSummary reservation, AssetSummary damaged) {
                return List.of();
            }
        };

        Optional<AssetSummary> empty = emptyStrategy.findSubstitute(booking, damaged);
        assertThat(empty).isEmpty();
    }
}

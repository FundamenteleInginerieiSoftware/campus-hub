package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reassignment strategy that selects an alternative substitute asset from the same category,
 * prioritizing equipment located in the same lab room.
 */
@Component
@Order(2)
@RequiredArgsConstructor
class SameCategoryStrategy implements SubstituteStrategy {

    private final CatalogApi catalogApi;
    private final ReservationApi reservationApi;

    @Override
    public String name() {
        return "SameCategoryStrategy";
    }

    @Override
    public Optional<AssetSummary> findSubstitute(ReservationSummary reservation, AssetSummary damaged) {
        List<AssetSummary> candidates = catalogApi.findAvailableByCategory(damaged.categoryId(), damaged.id());

        return candidates.stream()
                .sorted(Comparator.comparing((AssetSummary a) -> Objects.equals(a.labRoomId(), damaged.labRoomId()))
                        .reversed())
                .filter(candidate -> reservationApi.isAssetFree(
                        candidate.id(), reservation.start(), reservation.end(), reservation.id()))
                .findFirst();
    }
}

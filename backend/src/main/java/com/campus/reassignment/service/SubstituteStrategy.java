package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.reservation.api.ReservationSummary;
import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for selecting an alternative substitute asset to reassign bookings of a damaged asset.
 */
public interface SubstituteStrategy {

    String name();

    Optional<AssetSummary> findSubstitute(ReservationSummary reservation, AssetSummary damaged);

    /**
     * Returns all free candidate substitutes ordered by strategy preference (e.g. same lab room first,
     * then lowest ID) so the reassignment processor can try the next candidate of the same strategy
     * if the first one becomes busy concurrently (#149).
     */
    default List<AssetSummary> findCandidates(ReservationSummary reservation, AssetSummary damaged) {
        return findSubstitute(reservation, damaged).stream().toList();
    }
}

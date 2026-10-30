package com.campus.reassignment.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.reservation.api.ReservationSummary;
import java.util.Optional;

/**
 * Strategy interface for selecting an alternative substitute asset to reassign bookings of a damaged asset.
 */
public interface SubstituteStrategy {

    String name();

    Optional<AssetSummary> findSubstitute(ReservationSummary reservation, AssetSummary damaged);
}

package com.campus.project.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.ConflictException;
import com.campus.common.security.CurrentUser;
import com.campus.project.domain.Project;
import com.campus.project.domain.ProjectComponent;
import com.campus.project.web.ReserveProjectResponse;
import com.campus.project.web.ReservedItem;
import com.campus.reservation.api.ReservationApi;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Reserve all": picks one free unit per needed item and books the whole kit through Calin's
 * {@code ReservationApi.bookBundle}, all or nothing.
 */
@Service
@RequiredArgsConstructor
public class ProjectReservationService {

    private final ProjectLibrary projectLibrary;
    private final CatalogApi catalogApi;
    private final ReservationApi reservationApi;
    private final CurrentUser currentUser;

    // One transaction: bookBundle joins it, so if any unit fails inside it, nothing is saved.
    // The free-unit check below is only advisory. bookBundle locks every asset row (ascending id)
    // and checks overlaps, holds, booking rules and the student quota again, so a unit taken by
    // someone else in between ends in its RESERVATION_CONFLICT (or SLOT_HELD), not a double booking.
    @Transactional
    public ReserveProjectResponse reserve(String slug, LocalDateTime start, LocalDateTime end) {
        Project project = ProjectService.findProject(projectLibrary, slug);
        ProjectService.requireEndAfterStart(start, end);

        List<AssetSummary> picked = new ArrayList<>();
        for (ProjectComponent component : project.components()) {
            picked.addAll(pickUnits(component, start, end));
        }

        List<Long> assetIds = picked.stream().map(AssetSummary::id).toList();
        // The user id comes from the JWT, never from the request body. bookBundle lets an admin
        // book past the student limit, so Reserve all follows the same rule.
        UUID bundleId = reservationApi.bookBundle(currentUser.id(), assetIds, start, end);

        List<ReservedItem> items = picked.stream()
                .map(a -> new ReservedItem(a.id(), a.name(), a.model()))
                .toList();
        return new ReserveProjectResponse(bundleId, start, end, items);
    }

    // Lowest ids first, so the same request always picks the same units (like the reassignment
    // engine's tie-break, #99). A model appears only once per project (ProjectLibrary refuses
    // duplicates at startup), so the same unit can never be picked twice.
    private List<AssetSummary> pickUnits(ProjectComponent component, LocalDateTime start, LocalDateTime end) {
        // null excludeAssetId = exclude nothing, same call as ProjectService.details.
        List<AssetSummary> free = catalogApi.findAvailableByModel(component.model(), null).stream()
                .sorted(Comparator.comparing(AssetSummary::id))
                .filter(a -> reservationApi.isAssetFree(a.id(), start, end, null))
                .limit(component.quantity())
                .toList();
        if (free.size() < component.quantity()) {
            // Also the answer when the catalog has no unit of this model at all (not seeded yet).
            throw new ConflictException(
                    "PROJECT_COMPONENT_UNAVAILABLE",
                    "Not enough free units of '" + component.model() + "' in this slot: need " + component.quantity()
                            + ", found " + free.size());
        }
        return free;
    }
}

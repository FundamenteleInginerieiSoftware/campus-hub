package com.campus.project.service;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BadRequestException;
import com.campus.common.error.NotFoundException;
import com.campus.project.domain.Lang;
import com.campus.project.domain.Project;
import com.campus.project.domain.ProjectComponent;
import com.campus.project.web.ComponentView;
import com.campus.project.web.ProjectDetailsResponse;
import com.campus.project.web.ProjectListItem;
import com.campus.project.web.ProjectSearchResponse;
import com.campus.project.web.StepView;
import com.campus.reservation.api.ReservationApi;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectLibrary projectLibrary;
    private final ProjectSearch projectSearch;
    private final CatalogApi catalogApi;
    private final ReservationApi reservationApi;

    // The list view intentionally skips CatalogApi/ReservationApi so searching the in-memory
    // library makes zero database queries. That is also why there is no @Transactional here:
    // it would borrow a database connection for a request that never uses it.
    public ProjectSearchResponse search(String query, Lang lang) {
        SearchResult result = projectSearch.search(query, projectLibrary.all());
        List<ProjectListItem> items = result.hits().stream()
                .map(hit -> {
                    Project p = hit.project();
                    return new ProjectListItem(
                            p.slug(),
                            p.title().in(lang),
                            p.summary().in(lang),
                            p.difficulty(),
                            p.estimatedHours(),
                            p.components().size(),
                            hit.score());
                })
                .toList();
        return new ProjectSearchResponse(result.ambiguous(), items);
    }

    // One read-only transaction for all the catalog and reservation lookups of one page,
    // instead of a new one per component.
    @Transactional(readOnly = true)
    public ProjectDetailsResponse details(String slug, Lang lang, LocalDateTime start, LocalDateTime end) {
        Project project = findProject(projectLibrary, slug);
        requireWholeSlotOrNone(start, end);
        List<ComponentView> components = project.components().stream()
                .map(comp -> toComponentView(comp, lang, start, end))
                .toList();
        boolean reservable = components.stream().allMatch(c -> c.available() >= c.quantity());
        return toDetailsResponse(project, lang, components, start != null, reservable);
    }

    // Shared with ProjectReservationService, so details and Reserve all answer an unknown slug
    // and an empty slot with the same status and code.
    static Project findProject(ProjectLibrary projectLibrary, String slug) {
        return projectLibrary
                .find(slug)
                .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project '" + slug + "' was not found"));
    }

    static void requireEndAfterStart(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) {
            throw new BadRequestException("VALIDATION_FAILED", "end must be after start");
        }
    }

    // Both or neither: without a slot the page shows plain stock, with one it shows the units
    // free in that slot. Half a slot (only start or only end) is a 400.
    private static void requireWholeSlotOrNone(LocalDateTime start, LocalDateTime end) {
        if ((start == null) != (end == null)) {
            throw new BadRequestException("VALIDATION_FAILED", "Both start and end must be provided together");
        }
        if (start != null) {
            requireEndAfterStart(start, end);
        }
    }

    private ComponentView toComponentView(ProjectComponent comp, Lang lang, LocalDateTime start, LocalDateTime end) {
        int available = countFreeUnits(comp.model(), start, end);
        return new ComponentView(comp.model(), comp.quantity(), comp.why().in(lang), available);
    }

    // start == null means "no slot chosen": count every AVAILABLE unit of the model.
    private int countFreeUnits(String model, LocalDateTime start, LocalDateTime end) {
        // Liviu's CatalogApi.findAvailableByModel filters to AVAILABLE operational status;
        // passing null as excludeAssetId means "exclude nothing" (Spring Data turns IdNot(null)
        // into IS NOT NULL).
        List<AssetSummary> candidates = catalogApi.findAvailableByModel(model, null);
        if (start == null) {
            return candidates.size();
        }
        // Calin's ReservationApi.isAssetFree checks active bookings (CONFIRMED, CHECKED_OUT,
        // OVERDUE) in the slot; null ignoreReservationId excludes no reservation. It does not see
        // 1-minute holds, so a held unit still counts here; Reserve all then gets SLOT_HELD.
        return (int) candidates.stream()
                .filter(a -> reservationApi.isAssetFree(a.id(), start, end, null))
                .count();
    }

    private static ProjectDetailsResponse toDetailsResponse(
            Project project, Lang lang, List<ComponentView> components, boolean slotChecked, boolean reservable) {
        return new ProjectDetailsResponse(
                project.slug(),
                project.title().in(lang),
                project.summary().in(lang),
                project.difficulty(),
                project.estimatedHours(),
                components,
                project.bringYourself().stream().map(t -> t.in(lang)).toList(),
                project.steps().stream()
                        .map(s -> new StepView(s.title().in(lang), s.text().in(lang)))
                        .toList(),
                project.mistakes().stream().map(t -> t.in(lang)).toList(),
                slotChecked,
                reservable);
    }
}

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
    // library makes zero database queries.
    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    public ProjectDetailsResponse details(String slug, Lang lang, LocalDateTime start, LocalDateTime end) {
        Project project = findProject(projectLibrary, slug);
        if ((start == null) != (end == null)) {
            throw new BadRequestException("VALIDATION_FAILED", "Both start and end must be provided together");
        }
        if (start != null) {
            requireEndAfterStart(start, end);
        }

        boolean slotChecked = start != null;
        List<ComponentView> componentViews = project.components().stream()
                .map(comp -> toComponentView(comp, lang, slotChecked, start, end))
                .toList();
        boolean reservable = componentViews.stream().allMatch(c -> c.available() >= c.quantity());

        List<String> bringYourself =
                project.bringYourself().stream().map(t -> t.in(lang)).toList();
        List<StepView> steps = project.steps().stream()
                .map(s -> new StepView(s.title().in(lang), s.text().in(lang)))
                .toList();
        List<String> mistakes = project.mistakes().stream().map(t -> t.in(lang)).toList();

        return new ProjectDetailsResponse(
                project.slug(),
                project.title().in(lang),
                project.summary().in(lang),
                project.difficulty(),
                project.estimatedHours(),
                componentViews,
                bringYourself,
                steps,
                mistakes,
                slotChecked,
                reservable);
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

    private ComponentView toComponentView(
            ProjectComponent comp, Lang lang, boolean slotChecked, LocalDateTime start, LocalDateTime end) {
        // Liviu's CatalogApi.findAvailableByModel filters to AVAILABLE operational status;
        // passing null as excludeAssetId means "exclude nothing" (Spring Data turns IdNot(null)
        // into IS NOT NULL).
        List<AssetSummary> candidates = catalogApi.findAvailableByModel(comp.model(), null);
        int available;
        if (!slotChecked) {
            available = candidates.size();
        } else {
            // Calin's ReservationApi.isAssetFree checks active bookings (CONFIRMED, CHECKED_OUT,
            // OVERDUE) in the slot; null ignoreReservationId excludes no reservation. It does not see
            // 1-minute holds, so a held unit still counts here; Reserve all then gets SLOT_HELD.
            available = (int) candidates.stream()
                    .filter(a -> reservationApi.isAssetFree(a.id(), start, end, null))
                    .count();
        }
        return new ComponentView(comp.model(), comp.quantity(), comp.why().in(lang), available);
    }
}

package com.campus.incident.web;

import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.service.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/incidents")
@Tag(name = "Admin Incidents", description = "Endpoints for managing and resolving incidents")
public class AdminIncidentController {

    private final IncidentService incidentService;
    private final CurrentUser currentUser;

    public AdminIncidentController(IncidentService incidentService, CurrentUser currentUser) {
        this.incidentService = incidentService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List all incidents with pagination, optionally filtered by status")
    public PageResponse<IncidentResponse> getAllIncidents(
            @RequestParam(value = "status", required = false) IncidentStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return incidentService.getAllIncidents(status, pageable);
    }

    // The body is optional: resolutionNotes may be left out, so an empty request is valid too.
    @PostMapping("/{id}/resolve")
    @Operation(
            summary = "Resolve an incident report (OPEN or IN_REPAIR); the asset is available again once no "
                    + "other incident on it is still open")
    public IncidentResponse resolveIncident(
            @PathVariable("id") Long id, @Valid @RequestBody(required = false) ResolveIncidentRequest request) {
        String notes = request != null ? request.resolutionNotes() : null;
        return incidentService.resolveIncident(id, notes, currentUser.id());
    }
}

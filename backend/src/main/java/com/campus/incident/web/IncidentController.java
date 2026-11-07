package com.campus.incident.web;

import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.incident.service.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 401 (and 403 under /admin) are added to every endpoint by OpenApiConfig (#184); only the
// incident-specific errors are listed here. The codes are the ones in api-contract §5 (#189).
@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents", description = "Endpoints for reporting equipment incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final CurrentUser currentUser;

    public IncidentController(IncidentService incidentService, CurrentUser currentUser) {
        this.incidentService = incidentService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report an incident on an asset; the asset goes UNDER_MAINTENANCE")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Incident saved as OPEN; the asset is now UNDER_MAINTENANCE"),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED (assetId, description 1-2000 chars, severity) or MALFORMED_REQUEST"),
        @ApiResponse(responseCode = "404", description = "ASSET_NOT_FOUND"),
        @ApiResponse(responseCode = "409", description = "ASSET_RETIRED: nothing is saved")
    })
    public IncidentResponse reportIncident(@Valid @RequestBody CreateIncidentRequest request) {
        return incidentService.reportIncident(request, currentUser.id());
    }

    // "/me" is a fixed path, so Spring picks it before "/{id}".
    @GetMapping("/me")
    @Operation(summary = "The incidents I reported, newest first")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of my incidents"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_PARAMETER: page < 0, size outside 1-100, page * size too big, or not a number")
    })
    public PageResponse<IncidentResponse> getMyIncidents(
            @Parameter(description = "Page index, from 0") @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(value = "size", defaultValue = "20")
                    int size) {
        return incidentService.getMyIncidents(currentUser.id(), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One incident; only its reporter or an admin can see it (anyone else gets 404)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The incident"),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: the id is not a number"),
        @ApiResponse(
                responseCode = "404",
                description = "INCIDENT_NOT_FOUND: no such id, or someone else's incident (ids are not revealed)")
    })
    public IncidentResponse getIncident(@PathVariable("id") Long id) {
        return incidentService.getIncident(id, currentUser.id(), currentUser.isAdmin());
    }
}

package com.campus.incident.web;

import com.campus.common.security.CurrentUser;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.service.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents", description = "Endpoints for reporting, viewing, and resolving asset incidents")
@SecurityRequirement(name = "bearerAuth")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report an incident", description = "Report a damaged asset and trigger reassignment")
    @ApiResponse(responseCode = "201", description = "Incident reported successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    public ResponseEntity<IncidentResponse> reportIncident(
            @Valid @RequestBody CreateIncidentRequest request, @AuthenticationPrincipal CurrentUser currentUser) {
        IncidentResponse response = incidentService.reportIncident(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "List incidents for admins",
            description = "Retrieve all incidents with optional status filter")
    @ApiResponse(responseCode = "200", description = "List of incidents retrieved")
    @ApiResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    public ResponseEntity<List<IncidentResponse>> listIncidents(@RequestParam(required = false) IncidentStatus status) {
        List<IncidentResponse> responses = incidentService.getAllIncidents(status);
        return ResponseEntity.ok(responses);
    }

    @PatchMapping("/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Resolve an incident",
            description = "Mark incident as resolved and release asset back to available")
    @ApiResponse(responseCode = "200", description = "Incident resolved successfully")
    @ApiResponse(responseCode = "404", description = "Incident not found")
    @ApiResponse(responseCode = "403", description = "Forbidden - requires ADMIN role")
    public ResponseEntity<IncidentResponse> resolveIncident(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ResolveIncidentRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        IncidentResponse response = incidentService.resolveIncident(id, request, currentUser);
        return ResponseEntity.ok(response);
    }
}

package com.campus.incident.web;

import com.campus.common.security.CurrentUser;
import com.campus.incident.service.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents", description = "Endpoints for reporting equipment incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report an incident on an asset")
    public IncidentResponse reportIncident(@Valid @RequestBody CreateIncidentRequest request) {
        CurrentUser user = CurrentUser.fromSecurityContext();
        return incidentService.reportIncident(request, user.id());
    }
}

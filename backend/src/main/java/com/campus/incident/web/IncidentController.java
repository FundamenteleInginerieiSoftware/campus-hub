package com.campus.incident.web;

import com.campus.common.security.CurrentUser;
import com.campus.incident.service.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
@Tag(name = "Incidents", description = "Endpoints for reporting and managing equipment incidents")
public class IncidentController {

    private final IncidentService incidentService;

    @PostMapping
    @Operation(summary = "Report a damaged asset/equipment")
    public ResponseEntity<IncidentResponse> reportIncident(
            @Valid @RequestBody CreateIncidentRequest request, @AuthenticationPrincipal CurrentUser currentUser) {
        IncidentResponse response = incidentService.reportIncident(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}

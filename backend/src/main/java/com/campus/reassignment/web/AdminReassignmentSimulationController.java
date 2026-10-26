package com.campus.reassignment.web;

import com.campus.reassignment.service.MaintenanceImpactSimulator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for dry-run maintenance impact simulations.
 * Protected under {@code /api/v1/admin/**} by Matei's {@code SecurityConfig}.
 */
@Tag(name = "Admin Reassignment Simulation", description = "Dry-run impact analysis for planned asset maintenance")
@RestController
@RequestMapping("/api/v1/admin/reassignments")
@RequiredArgsConstructor
public class AdminReassignmentSimulationController {

    private final MaintenanceImpactSimulator simulator;

    @Operation(
            summary = "Simulate maintenance impact",
            description = "Runs a non-destructive dry-run of the reassignment engine for a planned maintenance window "
                    + "and recommends the lowest-disruption window")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Simulation report generated"),
        @ApiResponse(responseCode = "400", description = "Invalid request body"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(responseCode = "409", description = "Invalid maintenance time range")
    })
    @PostMapping("/simulate")
    public ResponseEntity<MaintenanceImpactResponse> simulate(@Valid @RequestBody SimulateMaintenanceRequest request) {
        return ResponseEntity.ok(simulator.simulate(request));
    }
}

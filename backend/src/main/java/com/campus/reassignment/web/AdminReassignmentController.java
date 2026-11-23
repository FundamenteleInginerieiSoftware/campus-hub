package com.campus.reassignment.web;

import com.campus.common.paging.PageResponse;
import com.campus.reassignment.service.MaintenanceImpactSimulator;
import com.campus.reassignment.service.ReassignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for querying automated disaster-recovery reassignment audit logs and dry-run simulations.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/reassignments")
@RequiredArgsConstructor
@Tag(name = "Admin Reassignments", description = "Audit log and maintenance impact simulation for reassignments")
public class AdminReassignmentController {

    private final ReassignmentService reassignmentService;
    private final MaintenanceImpactSimulator maintenanceImpactSimulator;

    @GetMapping
    @Operation(
            summary = "Get paginated reassignment logs",
            description = "Retrieves audit logs of automated reassignments and force-cancellations, newest first.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reassignment audit logs retrieved"),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Validation failed on query parameters (VALIDATION_FAILED, page < 0, size outside 1-100)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)")
    })
    public ResponseEntity<PageResponse<ReassignmentLogResponse>> getLogs(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(reassignmentService.getLogs(page, size));
    }

    @PostMapping("/simulate")
    @Operation(
            summary = "Simulate maintenance impact (dry-run)",
            description =
                    "Dry-runs the reassignment strategies across a planned maintenance window (max 90 days) for future CONFIRMED overlapping bookings without modifying the database.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Simulation result returned"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed or timezone offset provided (VALIDATION_FAILED, MALFORMED_REQUEST)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)"),
        @ApiResponse(responseCode = "404", description = "Asset not found (ASSET_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Invalid maintenance time window (INVALID_TIME_RANGE)")
    })
    public ResponseEntity<MaintenanceImpactResponse> simulate(@Valid @RequestBody SimulateMaintenanceRequest request) {
        return ResponseEntity.ok(maintenanceImpactSimulator.simulate(request));
    }
}

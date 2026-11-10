package com.campus.reassignment.web;

import com.campus.common.paging.PageResponse;
import com.campus.reassignment.service.ReassignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for querying automated disaster-recovery reassignment audit logs
 * and the global reassignment planner (greedy + branch and bound).
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/reassignments")
@RequiredArgsConstructor
@Tag(name = "Admin Reassignments", description = "Audit log and global optimal reassignment engine")
public class AdminReassignmentController {

    private final ReassignmentService reassignmentService;

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

    @PostMapping("/solve-optimal")
    @Operation(
            summary = "Solve global optimal reassignment plan (Admin)",
            description =
                    "Plans the reassignment of all upcoming reservations of an asset that is UNDER_MAINTENANCE using greedy + branch and bound, and shows the greedy result next to it. dryRun=true (default) only returns the plan; dryRun=false runs the standard reassignment engine (same strategies, retries and logging as the incident flow).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Optimal reassignment plan successfully calculated or executed",
                content = @Content(schema = @Schema(implementation = OptimalReassignmentPlanResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed on parameters",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication token missing or invalid",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Admin role required",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Damaged asset not found",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Asset is not UNDER_MAINTENANCE",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OptimalReassignmentPlanResponse> solveOptimal(
            @Parameter(description = "ID of the damaged or under-maintenance asset", required = true) @RequestParam
                    Long assetId,
            @Parameter(description = "Optional incident report ID linking to this maintenance event")
                    @RequestParam(required = false)
                    Long incidentId,
            @Parameter(
                            description =
                                    "If true (default), only returns the plan and changes nothing. Set false to execute it.")
                    @RequestParam(defaultValue = "true")
                    boolean dryRun) {
        return ResponseEntity.ok(
                OptimalReassignmentPlanResponse.from(reassignmentService.solveOptimalPlan(assetId, incidentId, dryRun)));
    }
}

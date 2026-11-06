package com.campus.reassignment.web;

import com.campus.common.paging.PageResponse;
import com.campus.reassignment.service.ReassignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for querying automated disaster-recovery reassignment audit logs.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/reassignments")
@RequiredArgsConstructor
@Tag(name = "Admin Reassignments", description = "Audit log and history of automated disaster-recovery reassignments")
public class AdminReassignmentController {

    private final ReassignmentService reassignmentService;

    @GetMapping
    @Operation(
            summary = "Get paginated reassignment logs",
            description = "Retrieves audit logs of automated reassignments and force-cancellations, newest first.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reassignment audit logs retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination parameters"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Admin role required")
    })
    public ResponseEntity<PageResponse<ReassignmentLogResponse>> getLogs(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(reassignmentService.getLogs(page, size));
    }
}

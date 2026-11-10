package com.campus.reassignment.web;

import com.campus.common.error.BadRequestException;
import com.campus.common.paging.PageResponse;
import com.campus.reassignment.service.ReassignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/chain-shift")
    @Operation(
            summary = "Resolve reservation conflict via augmenting path chain shift",
            description =
                    "Discovers an augmenting path chain shifting existing bookings to idle substitute assets to free the target asset.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Chain shift plan calculated and optionally applied"),
        @ApiResponse(responseCode = "400", description = "Bad parameters (startTime not before endTime, or a time with a Z/offset)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)"),
        @ApiResponse(responseCode = "404", description = "Target asset not found")
    })
    public ResponseEntity<ChainShiftPlanResponse> resolveChainShift(
            @RequestParam Long targetAssetId,
            // Same pattern as the reservation endpoints (#233): campus local time, a Z or offset is rejected.
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]") LocalDateTime endTime,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        if (!startTime.isBefore(endTime)) {
            throw new BadRequestException("VALIDATION_FAILED", "startTime must be before endTime");
        }
        return ResponseEntity.ok(reassignmentService.resolveChainShift(targetAssetId, startTime, endTime, dryRun));
    }
}

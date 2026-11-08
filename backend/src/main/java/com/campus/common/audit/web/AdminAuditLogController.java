package com.campus.common.audit.web;

import com.campus.common.audit.AuditService;
import com.campus.common.paging.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative controller for querying and inspecting audit log entries.
 *
 * <p>Protected by {@code SecurityConfig}: all requests under {@code /api/v1/admin/**} require the
 * {@code ADMIN} role (anonymous users receive 401, students receive 403).
 */
@RestController
@RequestMapping({"/api/v1/admin/audit-logs", "/api/v1/admin/audit-log"})
@RequiredArgsConstructor
@Tag(name = "Admin Audit Logs", description = "Query and inspect administrative audit trail entries")
public class AdminAuditLogController {

    private final AuditService auditService;

    @GetMapping
    @Operation(
            summary = "Search administrative audit logs",
            description =
                    "Queries the audit trail by actor ID, action code, entity type, entity ID, and date range with pagination.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Audit logs retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid query parameter or filter (INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized (missing or expired JWT)"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)")
    })
    public PageResponse<AuditLogResponse> search(
            @Parameter(description = "ID of the user who performed the action (null for system jobs)")
                    @RequestParam(required = false)
                    Long actorId,
            @Parameter(description = "Action code, e.g. ASSET_RETIRED, USER_DISABLED (case-insensitive)")
                    @RequestParam(required = false)
                    String action,
            @Parameter(description = "Domain entity type, e.g. ASSET, USER, INCIDENT (case-insensitive)")
                    @RequestParam(required = false)
                    String entityType,
            @Parameter(description = "ID of the affected entity") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Start timestamp (ISO-8601, inclusive)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @Parameter(description = "End timestamp (ISO-8601, inclusive)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1 to 100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort property and direction (e.g. createdAt,desc)")
                    @RequestParam(defaultValue = "createdAt,desc")
                    String sort) {
        return auditService.search(actorId, action, entityType, entityId, from, to, page, size, sort);
    }
}

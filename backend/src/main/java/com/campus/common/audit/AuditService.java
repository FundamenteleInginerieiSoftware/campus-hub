package com.campus.common.audit;

import com.campus.common.audit.web.AuditLogResponse;
import com.campus.common.paging.PageResponse;
import java.time.LocalDateTime;

/**
 * Records administrative actions across modules (catalog, incident triage, user admin, reservations)
 * and provides paginated search for the admin audit log view.
 *
 * <p>Persisted to the {@code audit_logs} table by {@link DatabaseAuditService}.
 */
public interface AuditService {

    /**
     * Records an audit entry for a sensitive action.
     *
     * @param actorId ID of the user who performed the action (or {@code null} for system jobs)
     * @param action machine-readable action code (e.g. {@code "ASSET_RETIRED"}, {@code "USER_DISABLED"})
     * @param entityType target domain type (e.g. {@code "ASSET"}, {@code "USER"}, {@code "INCIDENT"})
     * @param entityId ID of the affected entity
     * @param details human-readable summary of the change
     */
    void record(Long actorId, String action, String entityType, Long entityId, String details);

    /**
     * Searches administrative audit records matching filter criteria with pagination and sorting.
     *
     * @param actorId optional ID of the user who performed the action
     * @param action optional machine-readable action code
     * @param entityType optional domain entity type
     * @param entityId optional entity ID
     * @param from optional start timestamp (inclusive)
     * @param to optional end timestamp (inclusive)
     * @param page zero-based page index
     * @param size page size (1 to 100)
     * @param sort sort property and direction (e.g. "createdAt,desc")
     * @return paginated response containing matching audit entries
     */
    PageResponse<AuditLogResponse> search(
            Long actorId,
            String action,
            String entityType,
            Long entityId,
            LocalDateTime from,
            LocalDateTime to,
            int page,
            int size,
            String sort);
}

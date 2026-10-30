package com.campus.common.audit;

/**
 * Records administrative actions across modules (catalog, incident triage, user admin, reservations)
 * for the admin audit log view.
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
}

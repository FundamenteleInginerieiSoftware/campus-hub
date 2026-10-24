package com.campus.common.audit;

/**
 * Records administrative actions across modules (catalog, incident triage, user admin, reservations)
 * for the admin audit log view.
 *
 * <p>Backed by a logging stub in W3 and persisted to the {@code audit_logs} table from W4.
 */
public interface AuditService {

    /**
     * Records an audit entry for a sensitive action.
     *
     * @param actorId ID of the user who performed the action
     * @param action machine-readable action code (e.g. {@code "ASSET_RETIRED"}, {@code "USER_DISABLED"})
     * @param entityType target domain type (e.g. {@code "ASSET"}, {@code "USER"}, {@code "INCIDENT"})
     * @param entityId ID of the affected entity
     * @param details human-readable summary of the change
     */
    void record(Long actorId, String action, String entityType, Long entityId, String details);
}

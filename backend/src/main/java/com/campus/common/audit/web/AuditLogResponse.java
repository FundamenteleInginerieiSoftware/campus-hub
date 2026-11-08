package com.campus.common.audit.web;

import com.campus.common.audit.AuditLog;
import java.time.LocalDateTime;

/**
 * Public response DTO for an audit log record.
 */
public record AuditLogResponse(
        Long id,
        Long actorId,
        String action,
        String entityType,
        Long entityId,
        String details,
        LocalDateTime createdAt) {

    public static AuditLogResponse from(AuditLog entity) {
        return new AuditLogResponse(
                entity.getId(),
                entity.getActorId(),
                entity.getAction(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.getDetails(),
                entity.getCreatedAt());
    }
}

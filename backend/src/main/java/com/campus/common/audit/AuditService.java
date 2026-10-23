package com.campus.common.audit;

public interface AuditService {
    void record(Long actorId, String action, String entityType, Long entityId, String details);
}

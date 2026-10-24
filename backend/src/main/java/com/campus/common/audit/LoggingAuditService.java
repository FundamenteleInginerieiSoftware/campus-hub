package com.campus.common.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LoggingAuditService implements AuditService {
    @Override
    public void record(Long actorId, String action, String entityType, Long entityId, String details) {
        log.info(
                "Audit: actorId={}, action={}, entityType={}, entityId={}, details={}",
                actorId,
                action,
                entityType,
                entityId,
                details);
    }
}

package com.campus.common.audit;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists administrative audit entries to the {@code audit_logs} table using the shared campus
 * {@link Clock}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseAuditService implements AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    // Participates in the caller's transaction by default (REQUIRED), so if an admin action rolls
    // back due to a constraint/conflict, the audit entry rolls back with it too.
    @Override
    @Transactional
    public void record(Long actorId, String action, String entityType, Long entityId, String details) {
        AuditLog entry = new AuditLog(actorId, action, entityType, entityId, details, LocalDateTime.now(clock));
        auditLogRepository.save(entry);
        log.info(
                "Audit: actorId={}, action={}, entityType={}, entityId={}, details={}",
                actorId,
                action,
                entityType,
                entityId,
                details);
    }
}

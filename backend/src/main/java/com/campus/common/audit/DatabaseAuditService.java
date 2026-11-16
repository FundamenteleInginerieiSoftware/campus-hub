package com.campus.common.audit;

import com.campus.common.audit.web.AuditLogResponse;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.paging.PageRequests;
import com.campus.common.paging.PageResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists administrative audit entries to the {@code audit_logs} table using the shared campus
 * {@link Clock}, and provides paginated search queries for the admin audit log view.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseAuditService implements AuditService {

    private static final int MAX_FILTER_LENGTH = 64;
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("createdAt", "id", "action", "entityType", "actorId", "entityId");
    private static final Map<String, Sort.Direction> DEFAULT_SORT_DIRECTIONS = Map.of("createdAt", Sort.Direction.DESC);

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    // Participates in the caller's transaction by default (REQUIRED), so if an admin action rolls
    // back due to a constraint/conflict, the audit entry rolls back with it too.
    @Override
    @Transactional
    public void record(Long actorId, String action, String entityType, Long entityId, String details) {
        // Truncated to seconds so the in-memory timestamp matches PostgreSQL TIMESTAMP precision (#147).
        AuditLog entry = new AuditLog(
                actorId,
                action,
                entityType,
                entityId,
                details,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        auditLogRepository.save(entry);
        log.info(
                "Audit: actorId={}, action={}, entityType={}, entityId={}, details={}",
                actorId,
                action,
                entityType,
                entityId,
                details);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(
            Long actorId,
            String action,
            String entityType,
            Long entityId,
            LocalDateTime from,
            LocalDateTime to,
            int page,
            int size,
            String sort) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidParameterException("'from' timestamp must not be after 'to' timestamp");
        }
        if (action != null && action.trim().length() > MAX_FILTER_LENGTH) {
            throw new InvalidParameterException("Action filter must not exceed " + MAX_FILTER_LENGTH + " characters");
        }
        if (entityType != null && entityType.trim().length() > MAX_FILTER_LENGTH) {
            throw new InvalidParameterException(
                    "EntityType filter must not exceed " + MAX_FILTER_LENGTH + " characters");
        }

        Sort resolvedSort =
                PageRequests.parseSort(sort, "createdAt,desc", ALLOWED_SORT_FIELDS, DEFAULT_SORT_DIRECTIONS, true);
        Pageable pageable = PageRequests.of(page, size, resolvedSort);
        Page<AuditLog> result = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(actorId, action, entityType, entityId, from, to), pageable);
        return PageResponse.from(result, AuditLogResponse::from);
    }
}

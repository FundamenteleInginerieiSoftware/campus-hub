package com.campus.common.audit;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Dynamic Criteria predicates for querying administrative audit logs.
 *
 * <p>Dynamic predicates avoid the {@code (:param IS NULL OR ...)} JPQL anti-pattern,
 * which fails on PostgreSQL when null parameters lack an inferred SQL type.
 */
public final class AuditLogSpecification {

    private AuditLogSpecification() {}

    public static Specification<AuditLog> withFilters(
            Long actorId, String action, String entityType, Long entityId, LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }

            if (action != null && !action.isBlank()) {
                predicates.add(
                        cb.equal(cb.upper(root.get("action")), action.trim().toUpperCase(Locale.ROOT)));
            }

            if (entityType != null && !entityType.isBlank()) {
                predicates.add(cb.equal(
                        cb.upper(root.get("entityType")), entityType.trim().toUpperCase(Locale.ROOT)));
            }

            if (entityId != null) {
                predicates.add(cb.equal(root.get("entityId"), entityId));
            }

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }

            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}

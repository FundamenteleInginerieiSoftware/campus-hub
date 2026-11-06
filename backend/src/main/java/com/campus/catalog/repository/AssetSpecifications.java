package com.campus.catalog.repository;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class AssetSpecifications {

    private static final char LIKE_ESCAPE_CHAR = '\\';

    private AssetSpecifications() {}

    // Dynamic Criteria predicates avoid the "(:param is null or ...)" JPQL anti-pattern,
    // which fails on PostgreSQL when null parameters lack an inferred SQL type.
    public static Specification<Asset> withFilters(
            String search, Long categoryId, Long labRoomId, AssetStatus status, boolean excludeRetired) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String pattern = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE_CHAR),
                        cb.like(cb.lower(root.get("model")), pattern, LIKE_ESCAPE_CHAR),
                        cb.like(cb.lower(root.get("serialNumber")), pattern, LIKE_ESCAPE_CHAR)));
            }

            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }

            if (labRoomId != null) {
                predicates.add(cb.equal(root.get("labRoom").get("id"), labRoomId));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            // Students browsing /catalog should not see retired equipment, while admins on /admin/inventory see all.
            if (excludeRetired) {
                predicates.add(cb.notEqual(root.get("status"), AssetStatus.RETIRED));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // Escapes SQL LIKE wildcards (% and _) so user input such as "50%" or "R4_kit" matches literally.
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

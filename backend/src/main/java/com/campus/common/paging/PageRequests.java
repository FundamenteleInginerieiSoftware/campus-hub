package com.campus.common.paging;

import com.campus.common.error.InvalidParameterException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Shared pagination and sort-parsing helpers so list endpoints enforce the same bounds and
 * deterministic tie-breaking on {@code id}.
 */
public final class PageRequests {

    public static final int DEFAULT_MAX_PAGE_SIZE = 100;

    private PageRequests() {}

    /**
     * Validates pagination bounds and builds a {@link Pageable} using {@link #DEFAULT_MAX_PAGE_SIZE}.
     */
    public static Pageable of(int page, int size, Sort sort) {
        return of(page, size, DEFAULT_MAX_PAGE_SIZE, sort);
    }

    /**
     * Validates pagination bounds and builds a {@link Pageable}.
     */
    public static Pageable of(int page, int size, int maxPageSize, Sort sort) {
        validatePageBounds(page, size, maxPageSize);
        return PageRequest.of(page, size, sort);
    }

    public static void validatePageBounds(int page, int size, int maxPageSize) {
        if (page < 0) {
            throw new InvalidParameterException("Page index must not be negative");
        }
        if (size < 1 || size > maxPageSize) {
            throw new InvalidParameterException("Page size must be between 1 and " + maxPageSize);
        }
        // PageRequest.getOffset() multiplies page * size as long, but JPA/Hibernate casts or
        // PostgreSQL OFFSET expects a 32-bit-safe range; reject huge offsets as 400 instead of 500.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidParameterException("Page offset exceeds maximum supported value");
        }
    }

    /**
     * Parses a {@code field[,asc|desc]} sort parameter against an explicit whitelist, defaulting
     * unspecified directions to {@code ASC} and tie-breaking by {@code id ASC}.
     */
    public static Sort parseSort(String sort, String defaultSort, Set<String> allowedFields) {
        return parseSort(sort, defaultSort, allowedFields, Map.of(), false);
    }

    /**
     * Parses a {@code field[,asc|desc]} sort parameter against an explicit whitelist, allowing
     * per-field default directions and matching tie-break direction on {@code id}.
     */
    public static Sort parseSort(
            String sort,
            String defaultSort,
            Set<String> allowedFields,
            Map<String, Sort.Direction> defaultDirections,
            boolean tieBreakSameDirection) {
        String raw = (sort == null || sort.isBlank()) ? defaultSort : sort.trim();
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw new InvalidParameterException("Sort parameter must have format 'field,asc' or 'field,desc'");
        }

        String field = parts[0].trim();
        if (!allowedFields.contains(field)) {
            throw new InvalidParameterException("Unsupported sort property '" + field + "'");
        }

        Sort.Direction direction = defaultDirections.getOrDefault(field, Sort.Direction.ASC);
        if (parts.length == 2 && !parts[1].isBlank()) {
            String dirToken = parts[1].trim().toLowerCase(Locale.ROOT);
            direction = switch (dirToken) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default ->
                    throw new InvalidParameterException(
                            "Invalid sort direction '" + parts[1].trim() + "'; expected 'asc' or 'desc'");
            };
        }

        Sort.Direction tieBreakDirection = tieBreakSameDirection ? direction : Sort.Direction.ASC;
        return Sort.by(direction, field).and(Sort.by(tieBreakDirection, "id"));
    }
}

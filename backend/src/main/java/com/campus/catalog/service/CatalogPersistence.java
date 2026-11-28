package com.campus.catalog.service;

import com.campus.common.error.ConflictException;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Shared persistence helpers for catalog services.
 */
final class CatalogPersistence {

    private static final String PG_UNIQUE_VIOLATION_SQLSTATE = "23505";

    static final Set<String> ASSET_SERIAL_CONSTRAINTS =
            Set.of("assets_serial_number_key", "uq_assets_serial_number_lower");
    static final Set<String> CATEGORY_NAME_CONSTRAINTS = Set.of("categories_name_key", "uq_categories_name_lower");
    static final Set<String> LAB_ROOM_CODE_CONSTRAINTS = Set.of("lab_rooms_code_key", "uq_lab_rooms_code_lower");

    private CatalogPersistence() {}

    /**
     * Executes a flushing save and maps concurrent PostgreSQL unique-constraint violations
     * (SQLState {@code 23505}) to a domain {@link ConflictException}, while rethrowing unrelated
     * integrity failures untouched.
     *
     * <p>Bug fixed (#363): previously this caught {@link DataIntegrityViolationException} and ran an
     * {@code existsBy...} query on the same aborted PostgreSQL transaction / poisoned Hibernate
     * session, causing HTTP 500 ({@code AssertionFailure} / {@code 25P02}) under real concurrency.
     * Fix: inspect the exception cause chain without touching the database.
     */
    static <T> T saveOrThrowConflict(
            Supplier<T> saveAndFlush, Set<String> uniqueConstraintNames, Supplier<ConflictException> conflictFactory) {
        try {
            return saveAndFlush.get();
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueConstraintViolation(ex, uniqueConstraintNames)) {
                throw conflictFactory.get();
            }
            throw ex;
        }
    }

    private static boolean isUniqueConstraintViolation(
            DataIntegrityViolationException ex, Set<String> expectedConstraintNames) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException hibernateEx) {
                if (PG_UNIQUE_VIOLATION_SQLSTATE.equals(hibernateEx.getSQLState())) {
                    String constraintName = hibernateEx.getConstraintName();
                    return constraintName == null || matchesConstraint(constraintName, expectedConstraintNames);
                }
                return false;
            }
            if (cause instanceof SQLException sqlEx) {
                return PG_UNIQUE_VIOLATION_SQLSTATE.equals(sqlEx.getSQLState());
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean matchesConstraint(String actualName, Set<String> expectedNames) {
        if (expectedNames == null || expectedNames.isEmpty()) {
            return true;
        }
        String normalized = actualName.trim().toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < normalized.length()) {
            normalized = normalized.substring(dot + 1);
        }
        return expectedNames.contains(normalized);
    }
}

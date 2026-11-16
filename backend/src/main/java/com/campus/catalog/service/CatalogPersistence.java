package com.campus.catalog.service;

import com.campus.common.error.ConflictException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Shared persistence helpers for catalog services.
 */
final class CatalogPersistence {

    private CatalogPersistence() {}

    /**
     * Executes a flushing save and maps concurrent unique-constraint violations to a domain
     * {@link ConflictException}, while rethrowing unrelated constraint failures untouched.
     */
    static <T> T saveOrThrowConflict(
            Supplier<T> saveAndFlush, BooleanSupplier isDuplicate, Supplier<ConflictException> conflictFactory) {
        try {
            return saveAndFlush.get();
        } catch (DataIntegrityViolationException ex) {
            // Only map to conflict if the unique field is actually taken; unrelated integrity
            // errors (e.g. null FK or DB error) propagate as-is.
            if (isDuplicate.getAsBoolean()) {
                throw conflictFactory.get();
            }
            throw ex;
        }
    }
}

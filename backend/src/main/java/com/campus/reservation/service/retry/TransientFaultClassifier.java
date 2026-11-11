package com.campus.reservation.service.retry;

import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Classifies exceptions to identify transient PostgreSQL concurrency faults
 * such as deadlocks (SQLSTATE 40P01), lock acquisition timeouts (SQLSTATE 55P03),
 * and serialization failures (SQLSTATE 40001).
 */
public final class TransientFaultClassifier {

    private static final Set<String> RETRYABLE_SQL_STATES = Set.of(
            "40P01", // PostgreSQL deadlock_detected
            "55P03", // PostgreSQL lock_not_available
            "40001" // PostgreSQL serialization_failure
            );

    private TransientFaultClassifier() {}

    public static boolean isTransientFault(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // Direct Spring DAO concurrency exception checks
        if (throwable instanceof CannotAcquireLockException
                || throwable instanceof DeadlockLoserDataAccessException
                || throwable instanceof PessimisticLockingFailureException
                || throwable instanceof ConcurrencyFailureException) {
            return true;
        }

        // Inspect cause hierarchy for nested SQLExceptions or transient concurrency errors
        Throwable cause = throwable.getCause();
        while (cause != null) {
            if (cause instanceof CannotAcquireLockException
                    || cause instanceof DeadlockLoserDataAccessException
                    || cause instanceof PessimisticLockingFailureException
                    || cause instanceof ConcurrencyFailureException) {
                return true;
            }

            if (cause instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if (sqlState != null && RETRYABLE_SQL_STATES.contains(sqlState)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }

        return false;
    }
}

package com.campus.reservation.service.retry;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resilient retry executor that intercepts transient database concurrency faults
 * (PostgreSQL deadlocks, lock timeouts) and retries the operation with exponential
 * backoff and full randomized jitter.
 */
@Slf4j
@Component
public class TransientFaultRetryExecutor {

    private final RetryPolicy retryPolicy;
    private final RetryMetrics retryMetrics;
    private final Sleeper sleeper;

    @org.springframework.beans.factory.annotation.Autowired
    public TransientFaultRetryExecutor(RetryMetrics retryMetrics) {
        this(RetryPolicy.defaultPolicy(), retryMetrics, Thread::sleep);
    }

    public TransientFaultRetryExecutor(RetryPolicy retryPolicy, RetryMetrics retryMetrics, Sleeper sleeper) {
        this.retryPolicy = retryPolicy;
        this.retryMetrics = retryMetrics;
        this.sleeper = sleeper;
    }

    public <T> T executeWithRetry(Supplier<T> action, String operationName) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                T result = action.get();
                if (attempt > 1) {
                    retryMetrics.recordSuccessfulRecovery();
                    log.info(
                            "Transient database fault successfully recovered for operation '{}' on attempt {}/{}",
                            operationName,
                            attempt,
                            retryPolicy.maxAttempts());
                }
                return result;
            } catch (RuntimeException | Error ex) {
                if (!TransientFaultClassifier.isTransientFault(ex)) {
                    throw ex;
                }

                if (attempt >= retryPolicy.maxAttempts()) {
                    retryMetrics.recordExhausted();
                    log.error(
                            "Transient database fault retry limit reached (attempt {}/{}) for operation '{}': {}",
                            attempt,
                            retryPolicy.maxAttempts(),
                            operationName,
                            ex.getMessage());
                    throw ex;
                }

                retryMetrics.recordRetryAttempt();
                long delayMillis = computeBackoffDelay(attempt);
                log.warn(
                        "Transient database fault detected in '{}' (attempt {}/{}); retrying in {} ms: {}",
                        operationName,
                        attempt,
                        retryPolicy.maxAttempts(),
                        delayMillis,
                        ex.getClass().getSimpleName());

                try {
                    sleeper.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Retry wait interrupted during operation: " + operationName, ie);
                }
            }
        }
    }

    public long computeBackoffDelay(int attempt) {
        long exponential = (long)
                (retryPolicy.initialBackoff().toMillis() * Math.pow(retryPolicy.backoffMultiplier(), attempt - 1));
        long capped = Math.min(retryPolicy.maxBackoff().toMillis(), exponential);
        if (!retryPolicy.jitterEnabled() || capped <= 10) {
            return Math.max(1, capped);
        }
        // Full randomized jitter between 10ms and capped backoff delay
        return ThreadLocalRandom.current().nextLong(10, capped + 1);
    }

    public RetryMetrics getMetrics() {
        return retryMetrics;
    }
}

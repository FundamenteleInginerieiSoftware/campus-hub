package com.campus.reservation.service.retry;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory thread-safe operational metrics for transient database fault recoveries.
 */
@Component
public class RetryMetrics {

    private final AtomicLong totalRetries = new AtomicLong(0);
    private final AtomicLong successfulRecoveries = new AtomicLong(0);
    private final AtomicLong exhaustedRetries = new AtomicLong(0);

    public void recordRetryAttempt() {
        totalRetries.incrementAndGet();
    }

    public void recordSuccessfulRecovery() {
        successfulRecoveries.incrementAndGet();
    }

    public void recordExhausted() {
        exhaustedRetries.incrementAndGet();
    }

    public long getTotalRetries() {
        return totalRetries.get();
    }

    public long getSuccessfulRecoveries() {
        return successfulRecoveries.get();
    }

    public long getExhaustedRetries() {
        return exhaustedRetries.get();
    }

    public void reset() {
        totalRetries.set(0);
        successfulRecoveries.set(0);
        exhaustedRetries.set(0);
    }
}

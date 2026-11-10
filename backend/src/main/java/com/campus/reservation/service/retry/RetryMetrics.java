package com.campus.reservation.service.retry;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory counters for transient database fault retries. Exposed only through the admin endpoint
 * {@code GET /api/v1/admin/reservations/retry-metrics}; they reset when the application restarts.
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
}

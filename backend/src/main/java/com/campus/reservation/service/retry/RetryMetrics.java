package com.campus.reservation.service.retry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for transient database fault recoveries, tracked via in-memory counters
 * and registered in Micrometer MeterRegistry for Actuator export.
 */
@Component
public class RetryMetrics {

    private final AtomicLong totalRetries = new AtomicLong(0);
    private final AtomicLong successfulRecoveries = new AtomicLong(0);
    private final AtomicLong exhaustedRetries = new AtomicLong(0);

    private final Counter micrometerAttempts;
    private final Counter micrometerRecovered;
    private final Counter micrometerExhausted;

    @Autowired
    public RetryMetrics(@Autowired(required = false) MeterRegistry registry) {
        if (registry != null) {
            this.micrometerAttempts = Counter.builder("reservation.retry.attempts")
                    .description("Total retry attempts for transient concurrency conflicts")
                    .register(registry);
            this.micrometerRecovered = Counter.builder("reservation.retry.recovered")
                    .description("Total successful recoveries after transient database conflict retries")
                    .register(registry);
            this.micrometerExhausted = Counter.builder("reservation.retry.exhausted")
                    .description("Total operations failing after exhausting maximum retry budget")
                    .register(registry);
        } else {
            this.micrometerAttempts = null;
            this.micrometerRecovered = null;
            this.micrometerExhausted = null;
        }
    }

    public RetryMetrics() {
        this(null);
    }

    public void recordRetryAttempt() {
        totalRetries.incrementAndGet();
        if (micrometerAttempts != null) {
            micrometerAttempts.increment();
        }
    }

    public void recordSuccessfulRecovery() {
        successfulRecoveries.incrementAndGet();
        if (micrometerRecovered != null) {
            micrometerRecovered.increment();
        }
    }

    public void recordExhausted() {
        exhaustedRetries.incrementAndGet();
        if (micrometerExhausted != null) {
            micrometerExhausted.increment();
        }
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

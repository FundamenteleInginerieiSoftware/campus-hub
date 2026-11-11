package com.campus.reservation.service.retry;

import java.time.Duration;

/**
 * Configuration parameters for exponential backoff retry with randomized jitter.
 *
 * <p>Full jitter prevents the "thundering herd" lock stampede problem where multiple
 * concurrent threads experiencing PostgreSQL deadlocks simultaneously wake up at
 * identical intervals and collide again.
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double backoffMultiplier,
        boolean jitterEnabled) {

    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, Duration.ofMillis(50), Duration.ofMillis(1000), 2.0, true);
    }
}

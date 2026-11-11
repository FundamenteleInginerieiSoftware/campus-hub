package com.campus.reservation.service.retry;

/**
 * Functional abstraction for thread sleep delays to allow instantaneous deterministic unit testing.
 */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
}

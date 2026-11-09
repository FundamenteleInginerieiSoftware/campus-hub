package com.campus.reservation.service.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.ConflictException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

class TransientFaultRetryExecutorTest {

    private RetryMetrics retryMetrics;
    private AtomicInteger sleepCount;
    private Sleeper recordingSleeper;

    @BeforeEach
    void setUp() {
        retryMetrics = new RetryMetrics();
        sleepCount = new AtomicInteger(0);
        recordingSleeper = millis -> sleepCount.incrementAndGet();
    }

    @Test
    @DisplayName("Succeeds on first attempt without any retries or delays")
    void succeedsImmediatelyWithoutRetries() {
        TransientFaultRetryExecutor executor =
                new TransientFaultRetryExecutor(RetryPolicy.defaultPolicy(), retryMetrics, recordingSleeper);

        String result = executor.executeWithRetry(() -> "success", "createReservation");

        assertThat(result).isEqualTo("success");
        assertThat(sleepCount.get()).isEqualTo(0);
        assertThat(retryMetrics.getTotalRetries()).isEqualTo(0);
        assertThat(retryMetrics.getSuccessfulRecoveries()).isEqualTo(0);
        assertThat(retryMetrics.getExhaustedRetries()).isEqualTo(0);
    }

    @Test
    @DisplayName("Recovers on second attempt after PostgreSQL deadlock (SQLSTATE 40P01)")
    void recoversOnSecondAttemptAfterPostgreSqlDeadlock() {
        TransientFaultRetryExecutor executor =
                new TransientFaultRetryExecutor(RetryPolicy.defaultPolicy(), retryMetrics, recordingSleeper);

        AtomicInteger callCount = new AtomicInteger(0);

        String result = executor.executeWithRetry(
                () -> {
                    if (callCount.incrementAndGet() == 1) {
                        throw new DeadlockLoserDataAccessException(
                                "PostgreSQL deadlock detected", new SQLException("deadlock detected", "40P01"));
                    }
                    return "recovered-booking";
                },
                "createReservation");

        assertThat(result).isEqualTo("recovered-booking");
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(sleepCount.get()).isEqualTo(1);
        assertThat(retryMetrics.getTotalRetries()).isEqualTo(1);
        assertThat(retryMetrics.getSuccessfulRecoveries()).isEqualTo(1);
        assertThat(retryMetrics.getExhaustedRetries()).isEqualTo(0);
    }

    @Test
    @DisplayName("Recovers on third attempt after lock acquisition and pessimistic lock failure")
    void recoversOnThirdAttemptAfterLockTimeouts() {
        TransientFaultRetryExecutor executor = new TransientFaultRetryExecutor(
                new RetryPolicy(4, Duration.ofMillis(10), Duration.ofMillis(100), 2.0, false),
                retryMetrics,
                recordingSleeper);

        AtomicInteger callCount = new AtomicInteger(0);

        String result = executor.executeWithRetry(
                () -> {
                    int count = callCount.incrementAndGet();
                    if (count == 1) {
                        throw new CannotAcquireLockException(
                                "Lock wait timeout", new SQLException("could not obtain lock", "55P03"));
                    }
                    if (count == 2) {
                        throw new PessimisticLockingFailureException("Row lock contention");
                    }
                    return "recovered-bundle";
                },
                "createBundle");

        assertThat(result).isEqualTo("recovered-bundle");
        assertThat(callCount.get()).isEqualTo(3);
        assertThat(sleepCount.get()).isEqualTo(2);
        assertThat(retryMetrics.getTotalRetries()).isEqualTo(2);
        assertThat(retryMetrics.getSuccessfulRecoveries()).isEqualTo(1);
    }

    @Test
    @DisplayName("Exhausts all retry attempts and rethrows original deadlock exception")
    void exhaustsAllRetriesAndRethrows() {
        TransientFaultRetryExecutor executor = new TransientFaultRetryExecutor(
                new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50), 2.0, false),
                retryMetrics,
                recordingSleeper);

        AtomicInteger callCount = new AtomicInteger(0);

        assertThatThrownBy(() -> executor.executeWithRetry(
                        () -> {
                            callCount.incrementAndGet();
                            throw new DeadlockLoserDataAccessException(
                                    "Persistent deadlock", new SQLException("40P01"));
                        },
                        "deadlockTest"))
                .isInstanceOf(DeadlockLoserDataAccessException.class)
                .hasMessageContaining("Persistent deadlock");

        assertThat(callCount.get()).isEqualTo(3);
        assertThat(sleepCount.get()).isEqualTo(2);
        assertThat(retryMetrics.getTotalRetries()).isEqualTo(2);
        assertThat(retryMetrics.getExhaustedRetries()).isEqualTo(1);
        assertThat(retryMetrics.getSuccessfulRecoveries()).isEqualTo(0);
    }

    @Test
    @DisplayName("Fails fast on non-transient exceptions like ConflictException without retrying")
    void failsFastOnNonTransientExceptions() {
        TransientFaultRetryExecutor executor =
                new TransientFaultRetryExecutor(RetryPolicy.defaultPolicy(), retryMetrics, recordingSleeper);

        AtomicInteger callCount = new AtomicInteger(0);

        assertThatThrownBy(() -> executor.executeWithRetry(
                        () -> {
                            callCount.incrementAndGet();
                            throw new ConflictException("RESERVATION_CONFLICT", "Overlapping reservation exists");
                        },
                        "createReservation"))
                .isInstanceOf(ConflictException.class);

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(sleepCount.get()).isEqualTo(0);
        assertThat(retryMetrics.getTotalRetries()).isEqualTo(0);
    }

    @Test
    @DisplayName("Calculates jittered backoff within policy bounds")
    void calculatesJitteredBackoffWithinBounds() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(50), Duration.ofMillis(400), 2.0, true);
        TransientFaultRetryExecutor executor = new TransientFaultRetryExecutor(policy, retryMetrics, recordingSleeper);

        for (int attempt = 1; attempt <= 10; attempt++) {
            long delay = executor.computeBackoffDelay(attempt);
            assertThat(delay)
                    .as("Backoff delay for attempt %d must be at least 10ms and at most 400ms", attempt)
                    .isGreaterThanOrEqualTo(10)
                    .isLessThanOrEqualTo(400);
        }
    }

    @Test
    @DisplayName("Handles thread interruption during backoff delay cleanly")
    void handlesInterruptedSleep() {
        Sleeper interruptingSleeper = millis -> {
            throw new InterruptedException("Simulated interruption");
        };

        TransientFaultRetryExecutor executor =
                new TransientFaultRetryExecutor(RetryPolicy.defaultPolicy(), retryMetrics, interruptingSleeper);

        assertThatThrownBy(() -> executor.executeWithRetry(
                        () -> {
                            throw new CannotAcquireLockException("Transient connection failure");
                        },
                        "interruptedOp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Retry wait interrupted");
    }

    @Test
    @DisplayName("TransientFaultClassifier correctly identifies SQLSTATE and nested causes")
    void classifierRecognizesTransientErrors() {
        assertThat(TransientFaultClassifier.isTransientFault(null)).isFalse();
        assertThat(TransientFaultClassifier.isTransientFault(new IllegalArgumentException("invalid")))
                .isFalse();
        assertThat(TransientFaultClassifier.isTransientFault(new CannotAcquireLockException("lock")))
                .isTrue();
        assertThat(TransientFaultClassifier.isTransientFault(
                        new RuntimeException(new SQLException("deadlock detected", "40P01"))))
                .isTrue();
        assertThat(TransientFaultClassifier.isTransientFault(
                        new RuntimeException(new SQLException("lock timeout", "55P03"))))
                .isTrue();
        assertThat(TransientFaultClassifier.isTransientFault(
                        new RuntimeException(new SQLException("serialization failure", "40001"))))
                .isTrue();
        assertThat(TransientFaultClassifier.isTransientFault(
                        new RuntimeException(new SQLException("statement timeout", "57014"))))
                .isTrue();
    }
}
